package com.violinstudio.domain.feature.recording.usecase

import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.recording.model.DrainOutcome
import com.violinstudio.domain.feature.recording.model.FinalizeStatus
import com.violinstudio.domain.feature.recording.model.QueueState
import com.violinstudio.domain.feature.recording.model.QueuedRecording
import com.violinstudio.domain.feature.recording.model.RecordedTake
import com.violinstudio.domain.feature.recording.model.RecordingAccount
import com.violinstudio.domain.feature.recording.model.RecordingSpec
import com.violinstudio.domain.feature.recording.model.UploadError
import com.violinstudio.domain.feature.recording.model.UploadFailure
import com.violinstudio.domain.feature.recording.repository.FinalizeStatusSource
import com.violinstudio.domain.feature.recording.repository.Recorder
import com.violinstudio.domain.feature.recording.repository.RecordingAccountSource
import com.violinstudio.domain.feature.recording.repository.RecordingHandle
import com.violinstudio.domain.feature.recording.repository.RecordingUploader
import com.violinstudio.domain.feature.verifiedUser
import com.violinstudio.domain.testing.FakeRecordingQueue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Una ejecucion del trabajo de subida: vacia la cola del usuario, espera exponencial con tope y corta al cambiar de usuario. */
@OptIn(ExperimentalCoroutinesApi::class)
class DrainUploadQueueUseCaseTest {
    private class CountingUploader : RecordingUploader {
        val attempts = mutableListOf<Long>()
        var clock: () -> Long = { 0 }
        var fail: Boolean = true
        var throwing = false

        override suspend fun upload(item: QueuedRecording, onSession: suspend (String) -> Unit): Result<Unit> {
            attempts += clock()
            if (throwing) throw IllegalStateException("bug")
            return if (fail) Result.failure(UploadFailure(UploadError.Network)) else Result.success(Unit)
        }

        override suspend fun exists(item: QueuedRecording): Result<Boolean> = Result.success(false)
    }

    private class NoRecorder : Recorder {
        override suspend fun start(spec: RecordingSpec): Result<RecordingHandle> =
            Result.failure(IllegalStateException())

        override suspend fun deleteTake(take: RecordedTake) = Unit
    }

    private val auth = FakeAuthRepository()
    private val queue = FakeRecordingQueue()
    private val uploader = CountingUploader()
    private val finalize = object : FinalizeStatusSource {
        override suspend fun status(uid: String, id: String): Result<FinalizeStatus> =
            Result.success(FinalizeStatus.Ready)
    }
    private val accounts = object : RecordingAccountSource {
        override suspend fun current(uid: String): Result<RecordingAccount> = Result.failure(IllegalStateException())
    }
    private val drain = DrainUploadQueueUseCase(
        auth,
        ProcessUploadQueueUseCase(queue, uploader, finalize, accounts, NoRecorder()),
        minDelayMs = 1_000,
        maxDelayMs = 4_000,
        budgetMs = 20_000
    )

    private fun item(id: String, uid: String = verifiedUser.uid) =
        QueuedRecording(RecordedTake(id, uid, "/x/$id.m4a", 5_000, 1_000, 3))

    @Test
    fun `sin sesion termina sin subir nada`() = runTest {
        queue.items.value = listOf(item("r1"))
        assertEquals(DrainOutcome.DONE, drain())
        assertTrue(uploader.attempts.isEmpty())
    }

    @Test
    fun `con sesion sube lo pendiente y termina`() = runTest {
        queue.items.value = listOf(item("r1"))
        uploader.fail = false
        auth.user = verifiedUser
        assertEquals(DrainOutcome.DONE, drain())
        assertEquals(1, uploader.attempts.size)
        assertTrue(queue.items.value.isEmpty())
    }

    @Test
    fun `sin red reintenta con espera exponencial y pide reintento al agotar el presupuesto`() = runTest {
        uploader.clock = { testScheduler.currentTime }
        queue.items.value = listOf(item("r1"))
        auth.user = verifiedUser
        assertEquals(DrainOutcome.RETRY, drain())
        // 0, +1 s, +2 s, +4 s y despues cada 4 s (el maximo) hasta 20 s de espera acumulada.
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 11_000L, 15_000L, 19_000L), uploader.attempts)
        assertEquals(1, queue.items.value.size)
    }

    @Test
    fun `solo fallidas termina sin intentar`() = runTest {
        auth.user = verifiedUser
        queue.items.value = listOf(item("r1").copy(state = QueueState.FAILED))
        assertEquals(DrainOutcome.DONE, drain())
        assertTrue(uploader.attempts.isEmpty())
    }

    @Test
    fun `un error inesperado no la mata y cerrar sesion la corta`() = runTest {
        uploader.clock = { testScheduler.currentTime }
        uploader.throwing = true
        queue.items.value = listOf(item("r1"))
        auth.user = verifiedUser
        val outcome = async { drain() }
        advanceTimeBy(1_500)
        assertEquals(2, uploader.attempts.size)
        auth.user = null
        runCurrent()
        assertEquals(DrainOutcome.DONE, outcome.await())
        assertEquals(2, uploader.attempts.size)
    }
}
