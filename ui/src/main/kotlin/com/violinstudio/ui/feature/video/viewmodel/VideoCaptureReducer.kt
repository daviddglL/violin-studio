package com.violinstudio.ui.feature.video.viewmodel

object VideoCaptureReducer {
    fun reduce(state: VideoCaptureState, mutation: VideoCaptureMutation): VideoCaptureState = when (mutation) {
        VideoCaptureMutation.Starting -> state.copy(phase = VideoPhase.STARTING, prompt = null, message = null)

        is VideoCaptureMutation.Recording ->
            state.copy(phase = VideoPhase.RECORDING, elapsedMs = 0, maxDurationMs = mutation.maxDurationMs)

        is VideoCaptureMutation.Tick ->
            if (state.phase == VideoPhase.RECORDING) state.copy(elapsedMs = mutation.elapsedMs) else state

        VideoCaptureMutation.Stopping ->
            if (state.phase == VideoPhase.RECORDING) state.copy(phase = VideoPhase.STOPPING) else state

        VideoCaptureMutation.Blurring -> state.copy(phase = VideoPhase.BLURRING)

        VideoCaptureMutation.Idle -> state.copy(phase = VideoPhase.IDLE, elapsedMs = 0)

        is VideoCaptureMutation.Prompt -> state.copy(prompt = mutation.prompt)

        is VideoCaptureMutation.Message -> state.copy(message = mutation.message)
    }
}
