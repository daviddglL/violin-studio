package com.violinstudio.domain.feature.recording.usecase

import com.violinstudio.domain.feature.recording.model.QueueState
import com.violinstudio.domain.feature.recording.model.QueuedRecording
import com.violinstudio.domain.feature.recording.model.RecordedTake
import com.violinstudio.domain.feature.recording.model.RecordingSpec
import com.violinstudio.domain.feature.recording.repository.Recorder
import com.violinstudio.domain.feature.recording.repository.RecordingHandle
import com.violinstudio.domain.feature.recording.repository.RecordingQueue
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** REQ-REC-04 / REQ-REC-09: encolar una toma terminada y vaciar la cola (con sus ficheros) al revocar. */
class FinishRecordingUseCaseTest {
    private class FakeQueue : RecordingQueue {
        val items = mutableListOf<QueuedRecording>()
        var fail: Throwable? = null

        override suspend fun enqueue(item: QueuedRecording): Result<Unit> =
            fail?.let { Result.failure(it) } ?: Result.success(Unit).also { items += item }

        override fun observe(uid: String): Flow<List<QueuedRecording>> = flowOf(items.filter { it.take.uid == uid })

        override suspend fun update(
            uid: String,
            id: String,
            transform: (QueuedRecording) -> QueuedRecording
        ): Result<QueuedRecording?> = Result.success(null)

        override suspend fun remove(uid: String, id: String): Result<Unit> = Result.success(Unit)

        override suspend fun clear(uid: String): Result<List<QueuedRecording>> {
            fail?.let { return Result.failure(it) }
            val removed = items.filter { it.take.uid == uid }
            items.removeAll(removed)
            return Result.success(removed)
        }
    }

    private class FakeRecorder : Recorder {
        val deleted = mutableListOf<RecordedTake>()

        override suspend fun start(spec: RecordingSpec): Result<RecordingHandle> =
            Result.failure(IllegalStateException())

        override suspend fun deleteTake(take: RecordedTake) {
            deleted += take
        }
    }

    private val queue = FakeQueue()
    private val recorder = FakeRecorder()
    private fun take(id: String, uid: String = "A") = RecordedTake(id, uid, "/x/$id.m4a", 5_000, 1_000, 3)

    @Test
    fun `encola la toma como recorded con tarea y alumno opcionales`() = runTest {
        val result = FinishRecordingUseCase(queue)(take("r1"), assignmentId = "as1", sharedWith = "S")
        assertTrue(result.isSuccess)
        val item = queue.items.single()
        assertEquals(QueueState.RECORDED, item.state)
        assertEquals("as1", item.assignmentId)
        assertEquals("S", item.sharedWith)
        assertEquals(3, item.take.consentEpoch)
    }

    @Test
    fun `sin tarea ni alumno encola una grabacion libre y un fallo de la cola se propaga`() = runTest {
        FinishRecordingUseCase(queue)(take("r1"))
        assertEquals(null, queue.items.single().assignmentId)
        assertEquals(null, queue.items.single().sharedWith)
        queue.fail = IOException()
        assertTrue(FinishRecordingUseCase(queue)(take("r2")).isFailure)
        assertEquals(1, queue.items.size)
    }

    @Test
    fun `la revocacion vacia solo la cola del uid y borra sus ficheros`() = runTest {
        FinishRecordingUseCase(queue)(take("r1"))
        FinishRecordingUseCase(queue)(take("r2"))
        FinishRecordingUseCase(queue)(take("r3", uid = "B"))
        val result = ClearRecordingQueueUseCase(queue, recorder)("A")
        assertTrue(result.isSuccess)
        assertEquals(listOf("r1", "r2"), recorder.deleted.map { it.id })
        assertEquals(listOf("r3"), queue.items.map { it.id })
    }

    @Test
    fun `si la cola no se puede vaciar no se borra ningun fichero y el fallo se propaga`() = runTest {
        FinishRecordingUseCase(queue)(take("r1"))
        queue.fail = IOException()
        assertTrue(ClearRecordingQueueUseCase(queue, recorder)("A").isFailure)
        assertTrue(recorder.deleted.isEmpty())
    }
}
