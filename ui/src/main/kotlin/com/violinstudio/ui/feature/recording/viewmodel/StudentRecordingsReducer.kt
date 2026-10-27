package com.violinstudio.ui.feature.recording.viewmodel

import com.violinstudio.domain.feature.recording.model.Review

object StudentRecordingsReducer {
    fun reduce(state: StudentRecordingsState, mutation: StudentRecordingsMutation): StudentRecordingsState =
        when (mutation) {
            StudentRecordingsMutation.Loading -> state.copy(isLoading = true, loadFailed = false)

            is StudentRecordingsMutation.Loaded -> state.copy(
                isLoading = false,
                loadFailed = false,
                recordings = mutation.recordings,
                // Si la grabacion que se valoraba desaparece (el alumno la borro), el editor se cierra.
                editor = state.editor?.takeIf { editor -> mutation.recordings.any { it.id == editor.recordingId } }
            )

            StudentRecordingsMutation.LoadFailed ->
                state.copy(isLoading = false, loadFailed = true, recordings = emptyList(), editor = null)

            is StudentRecordingsMutation.Playback -> state.copy(playback = mutation.state)

            is StudentRecordingsMutation.EditorOpened -> state.copy(editor = ReviewEditor(mutation.recordingId))

            is StudentRecordingsMutation.EditorLoaded ->
                state.editor
                    ?.takeIf { it.recordingId == mutation.recordingId && it.loading }
                    ?.let { editor ->
                        state.copy(
                            editor = editor.copy(
                                loading = false,
                                existing = mutation.review != null,
                                verdict = mutation.review?.verdict,
                                text = mutation.review?.text.orEmpty()
                            )
                        )
                    } ?: state

            is StudentRecordingsMutation.VerdictChosen ->
                state.copy(editor = state.editor?.copy(verdict = mutation.verdict))

            is StudentRecordingsMutation.Text ->
                state.copy(editor = state.editor?.copy(text = mutation.text.take(Review.MAX_TEXT)))

            is StudentRecordingsMutation.Saving -> state.copy(editor = state.editor?.copy(saving = mutation.saving))

            StudentRecordingsMutation.EditorClosed -> state.copy(editor = null)

            is StudentRecordingsMutation.Message -> state.copy(message = mutation.message)
        }
}
