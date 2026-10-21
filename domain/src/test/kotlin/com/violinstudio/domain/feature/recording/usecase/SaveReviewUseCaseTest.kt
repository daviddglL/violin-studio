package com.violinstudio.domain.feature.recording.usecase

import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.recording.model.RecordingSummary
import com.violinstudio.domain.feature.recording.model.Review
import com.violinstudio.domain.feature.recording.model.Verdict
import com.violinstudio.domain.feature.recording.repository.RecordingReviews
import com.violinstudio.domain.feature.verifiedUser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SaveReviewUseCaseTest {
    private class FakeReviews : RecordingReviews {
        val saved = mutableListOf<List<Any>>()

        override fun observeStudentRecordings(studentUid: String): Flow<List<RecordingSummary>> = emptyFlow()

        override fun observeSharedDemos(teacherUid: String, studentUid: String): Flow<List<RecordingSummary>> =
            emptyFlow()

        override fun observeReview(ownerUid: String, recordingId: String, reviewerUid: String): Flow<Review?> =
            emptyFlow()

        override fun observeReviews(ownerUid: String, recordingId: String): Flow<List<Review>> = emptyFlow()

        override suspend fun save(
            ownerUid: String,
            recordingId: String,
            reviewerUid: String,
            verdict: Verdict,
            text: String
        ): Result<Unit> {
            saved += listOf(ownerUid, recordingId, reviewerUid, verdict, text)
            return Result.success(Unit)
        }
    }

    private val auth = FakeAuthRepository()
    private val reviews = FakeReviews()

    @Test
    fun `guarda como el profesor de la sesion con el texto recortado`() = runTest {
        auth.user = verifiedUser
        assertTrue(SaveReviewUseCase(auth, reviews)("s1", "r1", Verdict.APPROVED, "  Muy bien  ").isSuccess)
        assertEquals(listOf("s1", "r1", "u1", Verdict.APPROVED, "Muy bien"), reviews.saved.single())
    }

    @Test
    fun `sin sesion o con texto demasiado largo no guarda`() = runTest {
        assertTrue(SaveReviewUseCase(auth, reviews)("s1", "r1", Verdict.APPROVED, "").isFailure)
        auth.user = verifiedUser
        val long = "a".repeat(Review.MAX_TEXT + 1)
        assertTrue(SaveReviewUseCase(auth, reviews)("s1", "r1", Verdict.NEEDS_WORK, long).isFailure)
        assertTrue(reviews.saved.isEmpty())
        assertEquals("Review(verdict=APPROVED)", Review(Verdict.APPROVED, "secreto", null).toString())
        assertEquals(Verdict.NEEDS_WORK, Verdict.fromWire("needs_work"))
    }
}
