package com.violinstudio.domain.feature.recording.usecase

import com.violinstudio.domain.feature.FakeAuthRepository
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Motor de la cola: arranca con sesion y tomas pendientes, espera exponencial y se para al cambiar de usuario. */
@OptIn(ExperimentalCoroutinesApi::class)
class RunUploadQueueUseCaseTest {
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
    private val run = RunUploadQueueUseCase(
        auth,
        queue,
        ProcessUploadQueueUseCase(queue, uploader, finalize, accounts, NoRecorder()),
        minDelayMs = 1_000,
        maxDelayMs = 4_000
    )

    private fun item(id: String, uid: String = verifiedUser.uid) =
        QueuedRecording(RecordedTake(id, uid, "/x/$id.m4a", 5_000, 1_000, 3))

    @Test
    fun `sin sesion no hace nada y con sesion sube lo pendiente`() = runTest {
        queue.items.value = listOf(item("r1"))
        uploader.fail = false
        val job = launch { run() }
        runCurrent()
        assertTrue(uploader.attempts.isEmpty())
        auth.user = verifiedUser
        runCurrent()
        assertEquals(1, uploader.attempts.size)
        assertTrue(queue.items.value.isEmpty())
        job.cancel()
    }

    @Test
    fun `sin red reintenta con espera exponencial acotada`() = runTest {
        uploader.clock = { testScheduler.currentTime }
        queue.items.value = listOf(item("r1"))
        auth.user = verifiedUser
        val job = launch { run() }
        advanceTimeBy(20_000)
        // 0, +1 s, +2 s, +4 s y despues cada 4 s (el maximo).
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 11_000L, 15_000L, 19_000L), uploader.attempts)
        job.cancel()
    }

    @Test
    fun `una toma nueva despierta al motor parado`() = runTest {
        auth.user = verifiedUser
        uploader.fail = false
        val job = launch { run() }
        runCurrent()
        assertTrue(uploader.attempts.isEmpty())
        queue.enqueue(item("r1"))
        runCurrent()
        assertEquals(1, uploader.attempts.size)
        job.cancel()
    }

    @Test
    fun `una fallida no despierta al motor`() = runTest {
        auth.user = verifiedUser
        queue.items.value = listOf(item("r1").copy(state = QueueState.FAILED))
        val job = launch { run() }
        advanceTimeBy(10_000)
        assertTrue(uploader.attempts.isEmpty())
        job.cancel()
    }

    @Test
    fun `cerrar sesion para el motor y un error inesperado no lo mata`() = runTest {
        uploader.clock = { testScheduler.currentTime }
        uploader.throwing = true
        queue.items.value = listOf(item("r1"))
        auth.user = verifiedUser
        val job = launch { run() }
        advanceTimeBy(1_500)
        assertEquals(2, uploader.attempts.size)
        auth.user = null
        advanceTimeBy(60_000)
        assertEquals(2, uploader.attempts.size)
        job.cancel()
    }
}
