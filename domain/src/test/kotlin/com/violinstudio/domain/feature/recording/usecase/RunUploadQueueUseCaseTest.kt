package com.violinstudio.domain.feature.recording.usecase

import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.recording.model.QueueState
import com.violinstudio.domain.feature.recording.model.QueuedRecording
import com.violinstudio.domain.feature.recording.model.RecordedTake
import com.violinstudio.domain.feature.recording.repository.UploadScheduler
import com.violinstudio.domain.feature.verifiedUser
import com.violinstudio.domain.testing.FakeRecordingQueue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Vigia de la cola: programa la subida al haber tomas pendientes y la reinicia al empezar con cada usuario. */
@OptIn(ExperimentalCoroutinesApi::class)
class RunUploadQueueUseCaseTest {
    private class RecordingScheduler : UploadScheduler {
        val calls = mutableListOf<Boolean>()

        override fun schedule(restart: Boolean) {
            calls += restart
        }
    }

    private val auth = FakeAuthRepository()
    private val queue = FakeRecordingQueue()
    private val scheduler = RecordingScheduler()
    private val run = RunUploadQueueUseCase(auth, queue, scheduler)

    private fun item(id: String, uid: String = verifiedUser.uid) =
        QueuedRecording(RecordedTake(id, uid, "/x/$id.m4a", 5_000, 1_000, 3))

    @Test
    fun `sin sesion no programa y con sesion y pendientes reinicia el trabajo`() = runTest {
        queue.items.value = listOf(item("r1"))
        val job = launch { run() }
        runCurrent()
        assertTrue(scheduler.calls.isEmpty())
        auth.user = verifiedUser
        runCurrent()
        assertEquals(listOf(true), scheduler.calls)
        job.cancel()
    }

    @Test
    fun `sin pendientes no programa y una toma nueva lo programa sin reiniciar despues`() = runTest {
        auth.user = verifiedUser
        val job = launch { run() }
        runCurrent()
        assertTrue(scheduler.calls.isEmpty())
        queue.enqueue(item("r1"))
        runCurrent()
        queue.items.value = emptyList()
        runCurrent()
        queue.enqueue(item("r2"))
        runCurrent()
        assertEquals(listOf(true, false), scheduler.calls)
        job.cancel()
    }

    @Test
    fun `una fallida no programa`() = runTest {
        auth.user = verifiedUser
        queue.items.value = listOf(item("r1").copy(state = QueueState.FAILED))
        val job = launch { run() }
        runCurrent()
        assertTrue(scheduler.calls.isEmpty())
        job.cancel()
    }

    @Test
    fun `mas tomas mientras sigue pendiente no vuelven a programar`() = runTest {
        auth.user = verifiedUser
        queue.items.value = listOf(item("r1"))
        val job = launch { run() }
        runCurrent()
        queue.enqueue(item("r2"))
        runCurrent()
        assertEquals(listOf(true), scheduler.calls)
        job.cancel()
    }
}
