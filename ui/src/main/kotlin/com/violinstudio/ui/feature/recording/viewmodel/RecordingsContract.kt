package com.violinstudio.ui.feature.recording.viewmodel

import com.violinstudio.domain.feature.recording.model.PlaybackState
import com.violinstudio.domain.feature.recording.model.QueuedRecording
import com.violinstudio.domain.feature.recording.model.RecordingAccess
import com.violinstudio.domain.feature.recording.model.RecordingSummary
import com.violinstudio.domain.feature.recording.model.RecordingsOverview
import com.violinstudio.domain.feature.recording.model.Review
import com.violinstudio.ui.commons.mvi.UiEffect
import com.violinstudio.ui.commons.mvi.UiIntent
import com.violinstudio.ui.commons.mvi.UiState

enum class RecordingsMessage {
    LOAD_FAILED,
    OFFLINE,
    CONSENT_FAILED,
    GUARDIAN_REQUIRED,
    TEMPORARILY_UNAVAILABLE,
    QUOTA_REACHED,
    MIC_BUSY,
    TOO_SHORT,
    RECORDING_FAILED,
    SAVED,
    SAVE_FAILED,
    DELETE_FAILED,
    ACTION_FAILED,
    GUARDIAN_EMAIL_INVALID,
    GUARDIAN_MISMATCH,
    GUARDIAN_LEGACY,
    RATE_LIMITED
}

/** Permiso de micro: [RATIONALE] explica antes de volver a pedirlo; [BLOCKED] solo se arregla en Ajustes. */
enum class MicPrompt { RATIONALE, BLOCKED }

/**
 * [access] `null` mientras se comprueba (y tras un fallo, con [accessFailed]). [elapsedMs] no nulo mientras se graba.
 * [confirmDeleteId]: grabacion del servidor pendiente de confirmar su borrado. [reviewsFor]: grabacion cuyas
 * valoraciones estan abiertas; [reviews] `null` mientras cargan.
 */
data class RecordingsState(
    val access: RecordingAccess? = null,
    val accessFailed: Boolean = false,
    val busy: Boolean = false,
    val elapsedMs: Long? = null,
    val mic: MicPrompt? = null,
    val uid: String? = null,
    val pending: List<QueuedRecording> = emptyList(),
    val recordings: List<RecordingSummary> = emptyList(),
    val confirmRevoke: Boolean = false,
    val confirmDeleteId: String? = null,
    val message: RecordingsMessage? = null,
    val playback: PlaybackState = PlaybackState.Idle,
    val reviewsFor: String? = null,
    val reviews: List<Review>? = null,
    val reviewsFailed: Boolean = false,
    /** Email del tutor que escribe un menor para pedir el permiso; [guardianSentTo] el enmascarado del ultimo envio. */
    val guardianEmail: String = "",
    val guardianSentTo: String? = null
) : UiState {
    val isRecording: Boolean get() = elapsedMs != null

    override fun toString(): String =
        "RecordingsState(access=$access, recording=$isRecording, pending=${pending.size}, message=$message)"
}

sealed interface RecordingsIntent : UiIntent {
    data object Refresh : RecordingsIntent

    data object GrantConsent : RecordingsIntent

    data object AskRevoke : RecordingsIntent

    data object ConfirmRevoke : RecordingsIntent

    data object CancelRevoke : RecordingsIntent

    /** "Grabar"; [granted] y [rationale] los lee la pantalla con `MicPermissionChecker`. */
    data class Start(val granted: Boolean, val rationale: Boolean) : RecordingsIntent

    data class PermissionResult(val granted: Boolean, val rationale: Boolean) : RecordingsIntent

    data object ConfirmRationale : RecordingsIntent

    data object DismissMic : RecordingsIntent

    data object OpenAppSettings : RecordingsIntent

    /** "Parar": cierra la toma y la encola para subir. */
    data object Stop : RecordingsIntent

    /** `ON_STOP` o salir de la pantalla: una toma en curso se descarta (no hay grabacion en segundo plano). */
    data object Leave : RecordingsIntent

    data class Retry(val id: String) : RecordingsIntent

    data class Discard(val id: String) : RecordingsIntent

    data class AskDelete(val id: String) : RecordingsIntent

    data object ConfirmDelete : RecordingsIntent

    data object CancelDelete : RecordingsIntent

    data object DismissMessage : RecordingsIntent

    data class Play(val id: String) : RecordingsIntent

    data object StopPlayback : RecordingsIntent

    /** Abre las valoraciones de [id], o las cierra si ya estaban abiertas. */
    data class ToggleReviews(val id: String) : RecordingsIntent

    data class SetGuardianEmail(val email: String) : RecordingsIntent

    /** Un menor pide el permiso de grabacion a su tutor (mismo tutor que acepto la politica). */
    data object RequestGuardian : RecordingsIntent
}

sealed interface RecordingsEffect : UiEffect {
    data object RequestMicPermission : RecordingsEffect

    data object OpenAppSettings : RecordingsEffect
}

sealed interface RecordingsMutation {
    data object AccessLoading : RecordingsMutation

    data class AccessLoaded(val access: RecordingAccess) : RecordingsMutation

    data object AccessFailed : RecordingsMutation

    data class Overview(val overview: RecordingsOverview) : RecordingsMutation

    data class Busy(val busy: Boolean) : RecordingsMutation

    data class RevokePrompt(val shown: Boolean) : RecordingsMutation

    data class DeletePrompt(val id: String?) : RecordingsMutation

    data class Mic(val prompt: MicPrompt?) : RecordingsMutation

    data object RecordingStarted : RecordingsMutation

    data class Tick(val elapsedMs: Long) : RecordingsMutation

    data object RecordingEnded : RecordingsMutation

    data class Message(val message: RecordingsMessage?) : RecordingsMutation

    data class Playback(val state: PlaybackState) : RecordingsMutation

    data class ReviewsOpened(val id: String?) : RecordingsMutation

    data class ReviewsLoaded(val id: String, val reviews: List<Review>) : RecordingsMutation

    data class ReviewsFailed(val id: String) : RecordingsMutation

    data class GuardianEmail(val email: String) : RecordingsMutation

    data class GuardianSent(val emailMasked: String) : RecordingsMutation
}
