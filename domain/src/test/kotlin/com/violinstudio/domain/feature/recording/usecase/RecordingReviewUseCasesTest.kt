package com.violinstudio.domain.feature.recording.usecase

import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.recording.model.PlaybackState
import com.violinstudio.domain.feature.recording.model.RecordingSummary
import com.violinstudio.domain.feature.recording.model.Review
import com.violinstudio.domain.feature.recording.model.Verdict
import com.violinstudio.domain.feature.recording.repository.RecordingPlayer
import com.violinstudio.domain.feature.recording.repository.RecordingReviews
import com.violinstudio.domain.feature.verifiedUser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecordingReviewUseCasesTest {
    private val review = Review(Verdict.APPROVED, "Bien", 1L)
    private val recording = RecordingSummary("r1", 1L, 5_000, ready = true, isDemo = false, objectPath = "p/r1.m4a")

    private class FakeReviews(private val review: Review) : RecordingReviews {
        val calls = mutableListOf<String>()

        override fun observeStudentRecordings(studentUid: String): Flow<List<RecordingSummary>> {
            calls += "list:$studentUid"
            return flowOf(emptyList())
        }

        override fun observeReview(ownerUid: String, recordingId: String, reviewerUid: String): Flow<Review?> {
            calls += "one:$ownerUid/$recordingId/$reviewerUid"
            return flowOf(review)
        }

        override fun observeReviews(ownerUid: String, recordingId: String): Flow<List<Review>> {
            calls += "all:$ownerUid/$recordingId"
            return flowOf(listOf(review))
        }

        override suspend fun save(
            ownerUid: String,
            recordingId: String,
            reviewerUid: String,
            verdict: Verdict,
            text: String
        ): Result<Unit> = Result.success(Unit)
    }

    private class FakePlayer : RecordingPlayer {
        val played = mutableListOf<String>()
        var stops = 0
        override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState.Idle)

        override suspend fun play(recordingId: String, objectPath: String) {
            played += "$recordingId@$objectPath"
        }

        override fun stop() {
            stops++
        }
    }

    private val auth = FakeAuthRepository()
    private val reviews = FakeReviews(review)

    @Test
    fun `las revisiones se piden con el uid de la sesion`() = runTest {
        auth.user = verifiedUser
        assertEquals(review, ObserveMyReviewUseCase(auth, reviews)("s1", "r1").first())
        assertEquals(listOf(review), ObserveRecordingReviewsUseCase(auth, reviews)("r1").first())
        ObserveStudentRecordingsUseCase(reviews)("s1").first()
        assertEquals(listOf("one:s1/r1/u1", "all:u1/r1", "list:s1"), reviews.calls)
    }

    @Test
    fun `sin sesion no se observa nada`() = runTest {
        assertNull(withTimeoutOrNull(1_000) { ObserveMyReviewUseCase(auth, reviews)("s1", "r1").first() })
        assertNull(withTimeoutOrNull(1_000) { ObserveRecordingReviewsUseCase(auth, reviews)("r1").first() })
        assertTrue(reviews.calls.isEmpty())
    }

    @Test
    fun `solo reproduce grabaciones con ruta`() = runTest {
        val player = FakePlayer()
        val playback = RecordingPlaybackUseCase(player)
        playback.play(recording)
        playback.play(recording.copy(id = "r2", objectPath = null))
        playback.stop()
        assertEquals(listOf("r1@p/r1.m4a"), player.played)
        assertEquals(1, player.stops)
        assertEquals(PlaybackState.Idle, playback.state.value)
    }
}
