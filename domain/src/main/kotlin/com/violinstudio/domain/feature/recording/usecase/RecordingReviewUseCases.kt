package com.violinstudio.domain.feature.recording.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.recording.model.PlaybackState
import com.violinstudio.domain.feature.recording.model.RecordingSummary
import com.violinstudio.domain.feature.recording.model.Review
import com.violinstudio.domain.feature.recording.repository.RecordingPlayer
import com.violinstudio.domain.feature.recording.repository.RecordingReviews
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/** Grabaciones `ready` de un alumno vinculado (vista del profesor). El flujo falla si las reglas deniegan. */
class ObserveStudentRecordingsUseCase @Inject constructor(private val reviews: RecordingReviews) {
    operator fun invoke(studentUid: String): Flow<List<RecordingSummary>> = reviews.observeStudentRecordings(studentUid)
}

/**
 * Demos de su profesor [teacherUid] para la tarea [assignmentId] (vista del alumno), mas recientes primero; sin sesion
 * no emite. El flujo falla si las reglas deniegan (sin vinculo o sin consentimiento).
 */
class ObserveAssignmentDemosUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val reviews: RecordingReviews
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(teacherUid: String, assignmentId: String): Flow<List<RecordingSummary>> =
        auth.authUser.map { it?.uid }.distinctUntilChanged().flatMapLatest { uid ->
            if (uid == null) {
                emptyFlow()
            } else {
                reviews.observeSharedDemos(teacherUid, uid).map { demos ->
                    demos.filter { it.ready && it.assignmentId == assignmentId }
                        .sortedByDescending { it.createdAtMs ?: Long.MIN_VALUE }
                }
            }
        }
}

/** La revision del usuario actual (profesor) sobre una grabacion del alumno; sin sesion no emite. */
class ObserveMyReviewUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val reviews: RecordingReviews
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(studentUid: String, recordingId: String): Flow<Review?> =
        auth.authUser.map { it?.uid }.distinctUntilChanged().flatMapLatest { uid ->
            if (uid == null) emptyFlow() else reviews.observeReview(studentUid, recordingId, uid)
        }
}

/** Revisiones que han dejado sobre una grabacion propia (vista del alumno); sin sesion no emite. */
class ObserveRecordingReviewsUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val reviews: RecordingReviews
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(recordingId: String): Flow<List<Review>> =
        auth.authUser.map { it?.uid }.distinctUntilChanged().flatMapLatest { uid ->
            if (uid == null) emptyFlow() else reviews.observeReviews(uid, recordingId)
        }
}

/** Reproduccion en memoria de una grabacion: una sola a la vez. */
class RecordingPlaybackUseCase @Inject constructor(private val player: RecordingPlayer) {
    val state: StateFlow<PlaybackState> get() = player.state

    suspend fun play(recording: RecordingSummary) {
        val path = recording.objectPath ?: return
        player.play(recording.id, path, recording.kind)
    }

    fun stop() = player.stop()
}
