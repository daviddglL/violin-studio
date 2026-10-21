package com.violinstudio.domain.feature.recording.usecase

import app.cash.turbine.test
import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.recording.model.QueuedRecording
import com.violinstudio.domain.feature.recording.model.RecordedTake
import com.violinstudio.domain.feature.recording.model.RecordingSummary
import com.violinstudio.domain.feature.verifiedUser
import com.violinstudio.domain.testing.FakeRecordingLibrary
import com.violinstudio.domain.testing.FakeRecordingQueue
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecordingsOverviewUseCasesTest {
    private val auth = FakeAuthRepository()
    private val queue = FakeRecordingQueue()
    private val library = FakeRecordingLibrary()

    private fun take(id: String) = QueuedRecording(RecordedTake(id, verifiedUser.uid, "/x/$id.m4a", 5_000, 1, 0))

    private fun summary(id: String) = RecordingSummary(id, 1_000, 5_000, ready = true, isDemo = false)

    @Test
    fun `combina la cola y el servidor sin repetir lo ya subido`() = runTest {
        queue.items.value = listOf(take("r1"), take("r2"))
        library.items.value = listOf(summary("r2"))
        ObserveRecordingsUseCase(auth, queue, library)().test {
            auth.user = verifiedUser
            val overview = awaitItem()
            assertEquals("u1", overview.uid)
            assertEquals(listOf("r1"), overview.pending.map { it.id })
            assertEquals(listOf("r2"), overview.recordings.map { it.id })
            library.items.value = listOf(summary("r1"), summary("r2"))
            assertTrue(awaitItem().pending.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `borrar delega en la biblioteca`() = runTest {
        library.items.value = listOf(summary("r1"))
        assertTrue(DeleteRecordingUseCase(library)("u1", "r1").isSuccess)
        assertEquals(listOf("r1"), library.deleted)
        library.deleteResult = Result.failure(IOException())
        assertTrue(DeleteRecordingUseCase(library)("u1", "r2").isFailure)
    }
}
