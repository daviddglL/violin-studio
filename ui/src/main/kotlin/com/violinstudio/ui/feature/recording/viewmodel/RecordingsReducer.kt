package com.violinstudio.ui.feature.recording.viewmodel

object RecordingsReducer {
    fun reduce(state: RecordingsState, mutation: RecordingsMutation): RecordingsState = when (mutation) {
        RecordingsMutation.AccessLoading -> state.copy(access = null, accessFailed = false)
        is RecordingsMutation.AccessLoaded -> state.copy(access = mutation.access, accessFailed = false)
        RecordingsMutation.AccessFailed -> state.copy(access = null, accessFailed = true)
        is RecordingsMutation.Overview -> state.copy(
            uid = mutation.overview.uid,
            pending = mutation.overview.pending,
            recordings = mutation.overview.recordings
        )
        is RecordingsMutation.Busy -> state.copy(busy = mutation.busy)
        is RecordingsMutation.RevokePrompt -> state.copy(confirmRevoke = mutation.shown)
        is RecordingsMutation.DeletePrompt -> state.copy(confirmDeleteId = mutation.id)
        is RecordingsMutation.Mic -> state.copy(mic = mutation.prompt)
        RecordingsMutation.RecordingStarted -> state.copy(elapsedMs = 0, mic = null, message = null)
        is RecordingsMutation.Tick -> if (state.isRecording) state.copy(elapsedMs = mutation.elapsedMs) else state
        RecordingsMutation.RecordingEnded -> state.copy(elapsedMs = null)
        is RecordingsMutation.Message -> state.copy(message = mutation.message)
    }
}
