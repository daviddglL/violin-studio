package com.violinstudio.domain.feature.recording.repository

import com.violinstudio.domain.feature.recording.model.MediaKind
import com.violinstudio.domain.feature.recording.model.PlaybackState
import com.violinstudio.domain.feature.recording.model.RecordingSummary
import com.violinstudio.domain.feature.recording.model.Review
import com.violinstudio.domain.feature.recording.model.Verdict
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Revisiones de grabaciones (`users/{alumno}/recordings/{id}/reviews/{revisor}`). Las reglas deciden: el profesor
 * vinculado solo ve las `ready` de su alumno y solo lee y escribe SU revision; el dueno lee todas las suyas.
 */
interface RecordingReviews {
    /** Grabaciones `ready` del alumno, vistas por su profesor vinculado. El flujo falla si el listener falla. */
    fun observeStudentRecordings(studentUid: String): Flow<List<RecordingSummary>>

    /**
     * Demos `ready` que el profesor [teacherUid] comparte con [studentUid] (vista del alumno; las reglas exigen el
     * vinculo). El flujo falla si el listener falla.
     */
    fun observeSharedDemos(teacherUid: String, studentUid: String): Flow<List<RecordingSummary>>

    /** La revision de [reviewerUid] (`null` si aun no hay). */
    fun observeReview(ownerUid: String, recordingId: String, reviewerUid: String): Flow<Review?>

    /** Todas las revisiones de una grabacion propia (dueno). */
    fun observeReviews(ownerUid: String, recordingId: String): Flow<List<Review>>

    /** Crea o actualiza la revision de [reviewerUid]; espera al servidor (el profesor debe saber si se guardo). */
    suspend fun save(
        ownerUid: String,
        recordingId: String,
        reviewerUid: String,
        verdict: Verdict,
        text: String
    ): Result<Unit>
}

/** Reproduce una grabacion de Storage descargandola a memoria (las reglas deciden quien puede leerla). */
interface RecordingPlayer {
    val state: StateFlow<PlaybackState>

    /**
     * Para lo que suene y reproduce [objectPath]; los fallos quedan en [state] como `Failed`. Un [kind] video no suena:
     * queda en [state] como `Video` para que la pantalla lo muestre.
     */
    suspend fun play(recordingId: String, objectPath: String, kind: MediaKind = MediaKind.AUDIO)

    fun stop()
}
