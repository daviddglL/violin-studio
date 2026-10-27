package com.violinstudio.domain.feature.recording.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.recording.model.Review
import com.violinstudio.domain.feature.recording.model.Verdict
import com.violinstudio.domain.feature.recording.repository.RecordingReviews
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/** El texto se recorta y se limita a [Review.MAX_TEXT] antes de llegar a las reglas (que lo repiten). */
class SaveReviewUseCase @Inject constructor(private val auth: AuthRepository, private val reviews: RecordingReviews) {
    suspend operator fun invoke(studentUid: String, recordingId: String, verdict: Verdict, text: String): Result<Unit> {
        val reviewer = auth.authUser.first()?.uid ?: return Result.failure(IllegalStateException("Sin sesion"))
        val clean = text.trim()
        if (clean.length > Review.MAX_TEXT) return Result.failure(IllegalArgumentException("Texto demasiado largo"))
        return reviews.save(studentUid, recordingId, reviewer, verdict, clean)
    }
}
