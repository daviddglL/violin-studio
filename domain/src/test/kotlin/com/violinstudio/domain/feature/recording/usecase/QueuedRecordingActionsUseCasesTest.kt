package com.violinstudio.domain.feature.recording.usecase

import com.violinstudio.domain.feature.recording.model.FailReason
import com.violinstudio.domain.feature.recording.model.QueueState
import com.violinstudio.domain.feature.recording.model.QueuedRecording
import com.violinstudio.domain.feature.recording.model.RecordedTake
import com.violinstudio.domain.feature.recording.model.RecordingSpec
import com.violinstudio.domain.feature.recording.repository.Recorder
import com.violinstudio.domain.feature.recording.repository.RecordingHandle
import com.violinstudio.domain.testing.FakeRecordingQueue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueuedRecordingActionsUseCasesTest {
    private class FakeRecorder : Recorder {
        val deleted = mutableListOf<String>()

        override suspend fun start(spec: RecordingSpec): Result<RecordingHandle> =
            Result.failure(IllegalStateException())

        override suspend fun deleteTake(take: RecordedTake) {
            deleted += take.id
        }
    }

    private fun item(id: String, state: QueueState, error: FailReason? = null) =
        QueuedRecording(RecordedTake(id, "A", "/x/$id.m4a", 5_000, 1_000, 3), state = state, lastError = error)

    private val recorder = FakeRecorder()

    @Test
    fun `reintentar devuelve a la cola solo una fallida`() = runTest {
        val queue = FakeRecordingQueue(
            listOf(item("r1", QueueState.FAILED, FailReason.UNKNOWN), item("r2", QueueState.UPLOADED))
        )
        assertTrue(RetryUploadUseCase(queue)("A", "r1").isSuccess)
        assertEquals(QueueState.RECORDED, queue.get("r1")!!.state)
        assertNull(queue.get("r1")!!.lastError)
        RetryUploadUseCase(queue)("A", "r2")
        assertEquals(QueueState.UPLOADED, queue.get("r2")!!.state)
    }

    @Test
    fun `descartar borra fichero y elemento de una toma no subida`() = runTest {
        val queue = FakeRecordingQueue(listOf(item("r1", QueueState.FAILED), item("r2", QueueState.RECORDED)))
        assertTrue(DiscardUploadUseCase(queue, recorder)("A", "r1").isSuccess)
        assertTrue(DiscardUploadUseCase(queue, recorder)("A", "r2").isSuccess)
        assertEquals(listOf("r1", "r2"), recorder.deleted)
        assertTrue(queue.items.value.isEmpty())
        assertTrue(DiscardUploadUseCase(queue, recorder)("A", "nada").isSuccess)
    }

    @Test
    fun `no se descarta una toma que se esta subiendo y un fallo de la cola no borra el fichero`() = runTest {
        val queue = FakeRecordingQueue(listOf(item("r1", QueueState.UPLOADING), item("r2", QueueState.FAILED)))
        assertTrue(DiscardUploadUseCase(queue, recorder)("A", "r1").isFailure)
        queue.failWrites = true
        assertTrue(DiscardUploadUseCase(queue, recorder)("A", "r2").isFailure)
        assertTrue(recorder.deleted.isEmpty())
        assertEquals(2, queue.items.value.size)
    }
}
