package com.violinstudio.domain.feature.recording.usecase

import com.violinstudio.domain.feature.recording.model.QueueState
import com.violinstudio.domain.feature.recording.model.QueuedRecording
import com.violinstudio.domain.feature.recording.model.RecordedTake
import com.violinstudio.domain.feature.recording.model.RecordingSpec
import com.violinstudio.domain.feature.recording.repository.Recorder
import com.violinstudio.domain.feature.recording.repository.RecordingHandle
import com.violinstudio.domain.feature.recording.repository.RecordingQueue
import com.violinstudio.domain.feature.recording.repository.RecordingUploadScheduler
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** REQ-REC-04 / R3: encolar programa la subida; descartar cancela y deja que el worker limpie lo ya subido. */
class RecordingUploadSchedulingTest {
    private class FakeQueue : RecordingQueue {
        val items = mutableListOf<QueuedRecording>()
        var failEnqueue = false

        override suspend fun enqueue(item: QueuedRecording): Result<Unit> =
            if (failEnqueue) Result.failure(IOException()) else Result.success(Unit).also { items += item }

        override fun observe(uid: String): Flow<List<QueuedRecording>> = flow {
            emit(items.filter { it.take.uid == uid })
        }

        override suspend fun update(
            uid: String,
            id: String,
            transform: (QueuedRecording) -> QueuedRecording
        ): Result<QueuedRecording?> {
            val at = items.indexOfFirst { it.id == id && it.take.uid == uid }
            if (at < 0) return Result.success(null)
            items[at] = transform(items[at])
            return Result.success(items[at])
        }

        override suspend fun remove(uid: String, id: String): Result<Unit> {
            items.removeAll { it.id == id && it.take.uid == uid }
            return Result.success(Unit)
        }

        override suspend fun clear(uid: String): Result<List<QueuedRecording>> = Result.success(emptyList())
    }

    private class FakeScheduler : RecordingUploadScheduler {
        val calls = mutableListOf<String>()
        var failOnSchedule = false

        override fun schedule(uid: String, id: String) {
            calls += "schedule:$id"
            if (failOnSchedule) throw IllegalStateException("workmanager")
        }

        override fun cancel(id: String) {
            calls += "cancel:$id"
        }
    }

    private class FakeRecorder : Recorder {
        val deleted = mutableListOf<String>()

        override suspend fun start(spec: RecordingSpec): Result<RecordingHandle> =
            Result.failure(IllegalStateException())

        override suspend fun deleteTake(take: RecordedTake) {
            deleted += take.id
        }
    }

    private val queue = FakeQueue()
    private val scheduler = FakeScheduler()
    private val recorder = FakeRecorder()
    private fun take(id: String) = RecordedTake(id, "A", "/x/$id.m4a", 5_000, 1_000, 3)
    private fun queued(id: String, state: QueueState) = QueuedRecording(take(id), state = state)

    @Test
    fun `encolar programa la subida del id y un fallo de la cola no la programa`() = runTest {
        assertTrue(FinishRecordingUseCase(queue, scheduler)(take("r1")).isSuccess)
        assertEquals(listOf("schedule:r1"), scheduler.calls)
        queue.failEnqueue = true
        assertTrue(FinishRecordingUseCase(queue, scheduler)(take("r2")).isFailure)
        assertEquals(listOf("schedule:r1"), scheduler.calls)
    }

    @Test
    fun `si WorkManager falla la toma sigue encolada y el resultado es correcto`() = runTest {
        scheduler.failOnSchedule = true
        assertTrue(FinishRecordingUseCase(queue, scheduler)(take("r1")).isSuccess)
        assertEquals(listOf("r1"), queue.items.map { it.id })
    }

    @Test
    fun `descartar una toma sin empezar a subir borra fichero y elemento y cancela el trabajo`() = runTest {
        queue.items += queued("r1", QueueState.RECORDED)
        queue.items += queued("r2", QueueState.FAILED)
        assertTrue(DiscardRecordingUseCase(queue, scheduler, recorder)("A", "r1").isSuccess)
        assertTrue(DiscardRecordingUseCase(queue, scheduler, recorder)("A", "r2").isSuccess)
        assertEquals(listOf("r1", "r2"), recorder.deleted)
        assertTrue(queue.items.isEmpty())
        assertEquals(listOf("cancel:r1", "cancel:r2"), scheduler.calls)
    }

    @Test
    fun `descartar una toma subiendo o subida la marca cancelada y reprograma para que el worker limpie`() = runTest {
        queue.items += queued("r1", QueueState.UPLOADING)
        queue.items += queued("r2", QueueState.UPLOADED)
        assertTrue(DiscardRecordingUseCase(queue, scheduler, recorder)("A", "r1").isSuccess)
        assertTrue(DiscardRecordingUseCase(queue, scheduler, recorder)("A", "r2").isSuccess)
        assertTrue(queue.items.all { it.cancelled })
        assertTrue(recorder.deleted.isEmpty())
        assertEquals(listOf("cancel:r1", "schedule:r1", "cancel:r2", "schedule:r2"), scheduler.calls)
    }

    @Test
    fun `descartar un id que no esta en la cola es un no-op correcto`() = runTest {
        assertTrue(DiscardRecordingUseCase(queue, scheduler, recorder)("A", "nope").isSuccess)
        assertTrue(recorder.deleted.isEmpty())
        assertFalse(scheduler.calls.any { it.startsWith("schedule") })
    }
}
