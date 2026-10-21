package com.violinstudio.ui.feature.recording.viewmodel

object RecordingsReducer {
    fun reduce(state: RecordingsState, mutation: RecordingsMutation): RecordingsState = when (mutation) {
        RecordingsMutation.AccessLoading -> state.copy(access = null, accessFailed = false)
        is RecordingsMutation.AccessLoaded -> state.copy(access = mutation.access, accessFailed = false)
        RecordingsMutation.AccessFailed -> state.copy(access = null, accessFailed = true)
        is RecordingsMutation.Overview -> {
            val open = state.reviewsFor?.takeIf { id -> mutation.overview.recordings.any { it.id == id } }
            state.copy(
                uid = mutation.overview.uid,
                pending = mutation.overview.pending,
                recordings = mutation.overview.recordings,
                // Una grabacion borrada (aqui o en otro dispositivo) cierra sus valoraciones.
                reviewsFor = open,
                reviews = if (open == null) null else state.reviews,
                reviewsFailed = open != null && state.reviewsFailed
            )
        }
        is RecordingsMutation.Busy -> state.copy(busy = mutation.busy)
        is RecordingsMutation.RevokePrompt -> state.copy(confirmRevoke = mutation.shown)
        is RecordingsMutation.DeletePrompt -> state.copy(confirmDeleteId = mutation.id)
        is RecordingsMutation.Mic -> state.copy(mic = mutation.prompt)
        RecordingsMutation.RecordingStarted -> state.copy(elapsedMs = 0, mic = null, message = null)
        is RecordingsMutation.Tick -> if (state.isRecording) state.copy(elapsedMs = mutation.elapsedMs) else state
        RecordingsMutation.RecordingEnded -> state.copy(elapsedMs = null)
        is RecordingsMutation.Message -> state.copy(message = mutation.message)
        is RecordingsMutation.Playback -> state.copy(playback = mutation.state)
        is RecordingsMutation.ReviewsOpened ->
            state.copy(reviewsFor = mutation.id, reviews = null, reviewsFailed = false)
        is RecordingsMutation.ReviewsLoaded ->
            if (state.reviewsFor == mutation.id) {
                state.copy(
                    reviews = mutation.reviews,
                    reviewsFailed = false
                )
            } else {
                state
            }
        is RecordingsMutation.GuardianEmail -> state.copy(guardianEmail = mutation.email.take(MAX_EMAIL))
        is RecordingsMutation.GuardianSent -> state.copy(guardianEmail = "", guardianSentTo = mutation.emailMasked)
        is RecordingsMutation.ReviewsFailed ->
            if (state.reviewsFor == mutation.id) state.copy(reviews = null, reviewsFailed = true) else state
    }

    /** Limite de un email (RFC 5321) y de `requestGuardianConsent`. */
    const val MAX_EMAIL = 254
}
