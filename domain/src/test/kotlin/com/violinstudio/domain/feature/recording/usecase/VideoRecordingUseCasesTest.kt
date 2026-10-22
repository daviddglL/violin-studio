package com.violinstudio.domain.feature.recording.usecase

import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.FakeConsentRepository
import com.violinstudio.domain.feature.config
import com.violinstudio.domain.feature.profile.model.Role
import com.violinstudio.domain.feature.recording.failure.RecordingFailure
import com.violinstudio.domain.feature.recording.model.BlurredVideo
import com.violinstudio.domain.feature.recording.model.MediaKind
import com.violinstudio.domain.feature.recording.model.RecordingAccount
import com.violinstudio.domain.feature.recording.model.VideoSpec
import com.violinstudio.domain.feature.recording.repository.PendingUploads
import com.violinstudio.domain.feature.recording.repository.RecordingAccountSource
import com.violinstudio.domain.feature.recording.repository.VideoBlurrer
import com.violinstudio.domain.feature.recording.repository.VideoFiles
import com.violinstudio.domain.feature.verifiedUser
import com.violinstudio.domain.testing.FakeRecordingQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Video (4c): mismas comprobaciones que el audio mas `videoEnabled`, y el original sin difuminar nunca se queda. */
class VideoRecordingUseCasesTest {
    private class FakeFiles : VideoFiles {
        val existing = mutableSetOf<String>()
        val swept = mutableListOf<String>()

        override fun rawPath(uid: String, id: String) = "/nb/$uid/$id.raw.mp4"

        override fun outputPath(uid: String, id: String) = "/nb/$uid/$id.mp4"

        override suspend fun delete(uid: String, path: String) {
            existing -= path
        }

        override suspend fun sweepRaw(uid: String) {
            swept += uid
        }
    }

    private class FakeAccounts(var result: Result<RecordingAccount>) : RecordingAccountSource {
        override suspend fun current(uid: String): Result<RecordingAccount> = result
    }

    private class FakePending(var count: Int = 0) : PendingUploads {
        override suspend fun count(uid: String): Int = count
    }

    private class FakeBlurrer(private val files: FakeFiles) : VideoBlurrer {
        var result: (String) -> Result<BlurredVideo> = { Result.success(BlurredVideo(it, 30_000, 4_096, 1)) }
        var cancel = false

        override suspend fun blur(sourcePath: String, outputPath: String): Result<BlurredVideo> {
            if (cancel) throw CancellationException("pantalla cerrada")
            files.existing += outputPath
            return result(outputPath)
        }
    }

    private val auth = FakeAuthRepository().also { it.user = verifiedUser }
    private val consent = FakeConsentRepository().also {
        it.configResult = Result.success(
            config.copy(recordingsEnabled = true, videoEnabled = true, recordingConsentVersion = 2)
        )
    }
    private fun account(role: Role = Role.STUDENT, granted: Boolean = true, live: Int = 0, epoch: Long = 5) =
        RecordingAccount(role, false, true, granted, 2, epoch, live)

    private val accounts = FakeAccounts(Result.success(account()))
    private val pending = FakePending()
    private val files = FakeFiles()
    private val queue = FakeRecordingQueue()
    private val blurrer = FakeBlurrer(files)

    private fun start() = StartVideoUseCase(auth, consent, accounts, pending, files) { "vid-1" }

    private fun finish() = FinishVideoUseCase(blurrer, files, queue)

    private val spec = VideoSpec("vid-1", "u1", "/nb/u1/vid-1.raw.mp4", "/nb/u1/vid-1.mp4", 60_000, 59_500, 5)

    @Test
    fun `con todo vigente prepara la toma con rutas, limite por rol y epoca, y barre originales huerfanos`() = runTest {
        val got = start()().getOrThrow()
        assertEquals(spec, got)
        assertEquals(listOf("u1"), files.swept)
        accounts.result = Result.success(account(role = Role.TEACHER))
        assertEquals(119_500L, start()().getOrThrow().autoStopMs)
    }

    @Test
    fun `con VIDEO_ENABLED apagado es Disabled aunque el audio este encendido`() = runTest {
        consent.configResult = Result.success(config.copy(recordingsEnabled = true, recordingConsentVersion = 2))
        assertEquals(RecordingFailure.Disabled, start()().exceptionOrNull())
        assertTrue(files.swept.isEmpty())
    }

    @Test
    fun `consentimiento, cupo y sesion fallan igual que en audio`() = runTest {
        accounts.result = Result.success(account(granted = false))
        assertEquals(RecordingFailure.ConsentRequired, start()().exceptionOrNull())
        accounts.result = Result.success(account(live = 19))
        pending.count = 1
        assertEquals(RecordingFailure.QuotaReached, start()().exceptionOrNull())
        auth.user = null
        assertEquals(RecordingFailure.NoSession, start()().exceptionOrNull())
    }

    @Test
    fun `al terminar encola solo el difuminado como video con su version y borra el original`() = runTest {
        files.existing += spec.rawPath
        finish()(spec, assignmentId = "as-1").getOrThrow()
        val item = queue.items.value.single()
        assertEquals(MediaKind.VIDEO, item.take.kind)
        assertEquals(1, item.take.blurPolicyVersion)
        assertEquals(spec.outputPath, item.take.path)
        assertEquals(30_000L, item.take.durationMs)
        assertEquals(5L, item.take.consentEpoch)
        assertEquals("as-1", item.assignmentId)
        assertEquals(setOf(spec.outputPath), files.existing)
    }

    @Test
    fun `si el difuminado falla se borran original y salida y no se encola nada`() = runTest {
        files.existing += spec.rawPath
        blurrer.result = { Result.failure(RecordingFailure.BlurFailed) }
        assertEquals(RecordingFailure.BlurFailed, finish()(spec).exceptionOrNull())
        assertTrue(files.existing.isEmpty())
        assertTrue(queue.items.value.isEmpty())
    }

    @Test
    fun `cancelar durante el difuminado tambien borra el original`() = runTest {
        files.existing += spec.rawPath
        blurrer.cancel = true
        val result = runCatching { finish()(spec) }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertFalse(spec.rawPath in files.existing)
    }

    @Test
    fun `una toma demasiado corta se borra entera`() = runTest {
        blurrer.result = { Result.success(BlurredVideo(it, 400, 100, 1)) }
        assertEquals(RecordingFailure.TooShort, finish()(spec).exceptionOrNull())
        assertTrue(files.existing.isEmpty())
        assertTrue(queue.items.value.isEmpty())
    }

    @Test
    fun `si no se puede encolar se borra el difuminado`() = runTest {
        queue.failWrites = true
        assertTrue(finish()(spec).isFailure)
        assertTrue(files.existing.isEmpty())
    }

    @Test
    fun `la duracion se acota al limite del rol`() = runTest {
        blurrer.result = { Result.success(BlurredVideo(it, 60_400, 100, 1)) }
        finish()(spec).getOrThrow()
        assertEquals(60_000L, queue.items.value.single().take.durationMs)
    }

    @Test
    fun `descartar borra original y difuminado`() = runTest {
        files.existing += setOf(spec.rawPath, spec.outputPath)
        DiscardVideoUseCase(files)(spec)
        assertTrue(files.existing.isEmpty())
    }

    @Test
    fun `la spec no expone rutas, uid ni id en toString`() {
        assertFalse("u1" in spec.toString() || "vid" in spec.toString() || "nb" in spec.toString())
    }
}
