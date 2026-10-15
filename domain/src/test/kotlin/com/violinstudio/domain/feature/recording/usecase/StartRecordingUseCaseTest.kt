package com.violinstudio.domain.feature.recording.usecase

import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.FakeConsentRepository
import com.violinstudio.domain.feature.config
import com.violinstudio.domain.feature.profile.model.Role
import com.violinstudio.domain.feature.recording.failure.RecordingFailure
import com.violinstudio.domain.feature.recording.model.RecordedTake
import com.violinstudio.domain.feature.recording.model.RecordingAccount
import com.violinstudio.domain.feature.recording.model.RecordingOutcome
import com.violinstudio.domain.feature.recording.model.RecordingSpec
import com.violinstudio.domain.feature.recording.repository.PendingUploads
import com.violinstudio.domain.feature.recording.repository.Recorder
import com.violinstudio.domain.feature.recording.repository.RecordingAccountSource
import com.violinstudio.domain.feature.recording.repository.RecordingHandle
import com.violinstudio.domain.feature.verifiedUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** REQ-REC-02 / REQ-REC-14: ningun camino abre el micro sin consentimiento vigente, flags y cupo. */
class StartRecordingUseCaseTest {
    private class FakeHandle : RecordingHandle {
        override val outcome: StateFlow<RecordingOutcome?> = MutableStateFlow(null)

        override suspend fun stop(): Result<RecordedTake> = Result.failure(IllegalStateException())

        override suspend fun discard() = Unit
    }

    private class FakeRecorder : Recorder {
        val specs = mutableListOf<RecordingSpec>()
        val handle = FakeHandle()
        var result: Result<RecordingHandle>? = null

        override suspend fun start(spec: RecordingSpec): Result<RecordingHandle> {
            specs += spec
            return result ?: Result.success(handle)
        }

        override suspend fun deleteTake(take: RecordedTake) = Unit
    }

    private class FakeAccounts(var result: Result<RecordingAccount>) : RecordingAccountSource {
        var reads = 0

        override suspend fun current(uid: String): Result<RecordingAccount> {
            reads++
            return result
        }
    }

    private class FakePending(var count: Int = 0) : PendingUploads {
        override suspend fun count(uid: String): Int = count
    }

    private val auth = FakeAuthRepository().also { it.user = verifiedUser }
    private val consent = FakeConsentRepository().also {
        it.configResult = Result.success(
            config.copy(recordingsEnabled = true, minorRecordingEnabled = false, recordingConsentVersion = 2)
        )
    }
    private val recorder = FakeRecorder()
    private val pending = FakePending()
    private fun account(
        role: Role = Role.STUDENT,
        isMinor: Boolean = false,
        base: Boolean = true,
        granted: Boolean = true,
        version: Int = 2,
        epoch: Long = 0,
        live: Int = 0
    ) = RecordingAccount(role, isMinor, base, granted, version, epoch, live)

    private val accounts = FakeAccounts(Result.success(account()))
    private fun useCase() = StartRecordingUseCase(auth, consent, accounts, pending, recorder) { "rec-1" }

    private fun failureOf(result: Result<RecordingHandle>) = result.exceptionOrNull()

    @Test
    fun `con consentimiento y flags arranca con id, limite del alumno y epoca capturada`() = runTest {
        accounts.result = Result.success(account(epoch = 7))
        val result = useCase()()
        assertSame(recorder.handle, result.getOrThrow())
        val spec = recorder.specs.single()
        assertEquals("rec-1", spec.id)
        assertEquals("u1", spec.uid)
        assertEquals(60_000L, spec.maxDurationMs)
        assertEquals(59_500L, spec.autoStopMs)
        assertEquals(7L, spec.consentEpoch)
    }

    @Test
    fun `la epoca ausente del doc llega como 0 y el profesor graba hasta 120 s`() = runTest {
        accounts.result = Result.success(account(role = Role.TEACHER))
        useCase()().getOrThrow()
        val spec = recorder.specs.single()
        assertEquals(0L, spec.consentEpoch)
        assertEquals(120_000L, spec.maxDurationMs)
        assertEquals(119_500L, spec.autoStopMs)
    }

    @Test
    fun `sin sesion no hay grabacion`() = runTest {
        auth.user = null
        assertEquals(RecordingFailure.NoSession, failureOf(useCase()()))
        assertTrue(recorder.specs.isEmpty())
    }

    @Test
    fun `flag de grabaciones apagado es Disabled y no lee la cuenta`() = runTest {
        consent.configResult = Result.success(config.copy(recordingsEnabled = false))
        assertEquals(RecordingFailure.Disabled, failureOf(useCase()()))
        assertEquals(0, accounts.reads)
        assertTrue(recorder.specs.isEmpty())
    }

    @Test
    fun `un menor con MINOR_RECORDING_ENABLED apagado es Disabled y con el flag encendido puede grabar`() = runTest {
        accounts.result = Result.success(account(isMinor = true))
        assertEquals(RecordingFailure.Disabled, failureOf(useCase()()))
        consent.configResult = Result.success(
            config.copy(recordingsEnabled = true, minorRecordingEnabled = true, recordingConsentVersion = 2)
        )
        useCase()().getOrThrow()
        assertEquals(1, recorder.specs.size)
    }

    @Test
    fun `Disabled gana a ConsentRequired`() = runTest {
        accounts.result = Result.success(account(isMinor = true, granted = false))
        assertEquals(RecordingFailure.Disabled, failureOf(useCase()()))
    }

    @Test
    fun `sin consentimiento base o de grabacion o con version vieja es ConsentRequired`() = runTest {
        listOf(account(base = false), account(granted = false), account(version = 1)).forEach {
            accounts.result = Result.success(it)
            assertEquals(RecordingFailure.ConsentRequired, failureOf(useCase()()))
        }
        assertTrue(recorder.specs.isEmpty())
    }

    @Test
    fun `una version de consentimiento mayor que la vigente basta`() = runTest {
        accounts.result = Result.success(account(version = 3))
        useCase()().getOrThrow()
    }

    @Test
    fun `20 vivas o 19 vivas mas 1 en cola es QuotaReached y 19 mas 0 puede grabar`() = runTest {
        accounts.result = Result.success(account(live = 20))
        assertEquals(RecordingFailure.QuotaReached, failureOf(useCase()()))
        accounts.result = Result.success(account(live = 19))
        pending.count = 1
        assertEquals(RecordingFailure.QuotaReached, failureOf(useCase()()))
        pending.count = 0
        useCase()().getOrThrow()
        assertEquals(1, recorder.specs.size)
    }

    @Test
    fun `ConsentRequired gana a QuotaReached`() = runTest {
        accounts.result = Result.success(account(granted = false, live = 20))
        assertEquals(RecordingFailure.ConsentRequired, failureOf(useCase()()))
    }

    @Test
    fun `si no se puede leer la configuracion o la cuenta falla cerrado con Unavailable`() = runTest {
        consent.configResult = Result.failure(RuntimeException("x"))
        assertEquals(RecordingFailure.Unavailable, failureOf(useCase()()))
        consent.configResult = Result.success(config.copy(recordingsEnabled = true, recordingConsentVersion = 2))
        accounts.result = Result.failure(RuntimeException("x"))
        assertEquals(RecordingFailure.Unavailable, failureOf(useCase()()))
        assertTrue(recorder.specs.isEmpty())
    }

    @Test
    fun `el fallo del recorder se devuelve tal cual`() = runTest {
        recorder.result = Result.failure(RecordingFailure.MicUnavailable)
        assertEquals(RecordingFailure.MicUnavailable, failureOf(useCase()()))
    }

    @Test
    fun `spec y toma no exponen uid, id ni ruta en toString`() {
        val spec = RecordingSpec("rec-secret", "uid-secret", 60_000, 59_500, 3)
        val take = RecordedTake("rec-secret", "uid-secret", "/data/uid-secret/rec-secret.m4a", 1_000, 2_000, 3)
        listOf(spec.toString(), take.toString()).forEach {
            assertFalse("secret" in it, it)
        }
    }
}
