package com.violinstudio.ui.feature.recording.viewmodel

import com.violinstudio.domain.feature.recording.model.PlaybackState
import com.violinstudio.domain.feature.recording.model.RecordingSummary
import com.violinstudio.domain.feature.recording.model.Review
import com.violinstudio.domain.feature.recording.model.Verdict
import com.violinstudio.ui.commons.mvi.UiEffect
import com.violinstudio.ui.commons.mvi.UiIntent
import com.violinstudio.ui.commons.mvi.UiState

enum class StudentRecordingsMessage { SAVED, SAVE_FAILED, REVIEW_LOAD_FAILED }

/**
 * Revision que el profesor esta escribiendo. [loading] hasta leer la que ya hubiera; [existing] si ya habia una (se
 * actualiza en lugar de crear). Se puede guardar con un veredicto elegido y sin estar guardando.
 */
data class ReviewEditor(
    val recordingId: String,
    val loading: Boolean = true,
    val existing: Boolean = false,
    val verdict: Verdict? = null,
    val text: String = "",
    val saving: Boolean = false
) {
    val canSave: Boolean get() = !loading && !saving && verdict != null

    override fun toString(): String = "ReviewEditor(loading=$loading, verdict=$verdict, saving=$saving)"
}

/** Grabaciones `ready` de un alumno vinculado, vistas por su profesor: escuchar y valorar (REQ-RVW-01). */
data class StudentRecordingsState(
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val recordings: List<RecordingSummary> = emptyList(),
    val playback: PlaybackState = PlaybackState.Idle,
    val editor: ReviewEditor? = null,
    val message: StudentRecordingsMessage? = null
) : UiState {
    override fun toString(): String =
        "StudentRecordingsState(isLoading=$isLoading, loadFailed=$loadFailed, recordings=${recordings.size}, " +
            "editor=${editor != null}, message=$message)"
}

sealed interface StudentRecordingsIntent : UiIntent {
    data object Retry : StudentRecordingsIntent

    data class Play(val id: String) : StudentRecordingsIntent

    data object Stop : StudentRecordingsIntent

    data class OpenReview(val id: String) : StudentRecordingsIntent

    data class SetVerdict(val verdict: Verdict) : StudentRecordingsIntent

    data class SetText(val text: String) : StudentRecordingsIntent

    data object SaveReview : StudentRecordingsIntent

    data object CloseReview : StudentRecordingsIntent

    data object DismissMessage : StudentRecordingsIntent

    /** Salir de la pantalla o pasar a segundo plano: deja de sonar. */
    data object Leave : StudentRecordingsIntent
}

sealed interface StudentRecordingsEffect : UiEffect

sealed interface StudentRecordingsMutation {
    data object Loading : StudentRecordingsMutation

    data class Loaded(val recordings: List<RecordingSummary>) : StudentRecordingsMutation

    data object LoadFailed : StudentRecordingsMutation

    data class Playback(val state: PlaybackState) : StudentRecordingsMutation

    data class EditorOpened(val recordingId: String) : StudentRecordingsMutation

    data class EditorLoaded(val recordingId: String, val review: Review?) : StudentRecordingsMutation

    data class VerdictChosen(val verdict: Verdict) : StudentRecordingsMutation

    data class Text(val text: String) : StudentRecordingsMutation

    data class Saving(val saving: Boolean) : StudentRecordingsMutation

    data object EditorClosed : StudentRecordingsMutation

    data class Message(val message: StudentRecordingsMessage?) : StudentRecordingsMutation
}
