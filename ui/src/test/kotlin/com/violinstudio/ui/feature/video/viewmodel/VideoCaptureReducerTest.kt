package com.violinstudio.ui.feature.video.viewmodel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VideoCaptureReducerTest {
    private fun VideoCaptureState.then(mutation: VideoCaptureMutation) = VideoCaptureReducer.reduce(this, mutation)

    @Test
    fun `empezar limpia aviso y permiso, y grabar reinicia el tiempo`() {
        val state = VideoCaptureState(prompt = CameraPrompt.RATIONALE, message = VideoMessage.TOO_SHORT, elapsedMs = 9)
            .then(VideoCaptureMutation.Starting)
        assertEquals(VideoPhase.STARTING, state.phase)
        assertNull(state.prompt)
        assertNull(state.message)
        val recording = state.then(VideoCaptureMutation.Recording(60_000))
        assertEquals(VideoPhase.RECORDING, recording.phase)
        assertEquals(0L, recording.elapsedMs)
        assertEquals(60_000L, recording.maxDurationMs)
        assertTrue(recording.isCapturing)
    }

    @Test
    fun `el tiempo y la parada solo cuentan grabando`() {
        val idle = VideoCaptureState()
        assertEquals(idle, idle.then(VideoCaptureMutation.Tick(1_000)))
        assertEquals(idle, idle.then(VideoCaptureMutation.Stopping))
        val recording = idle.then(VideoCaptureMutation.Recording(60_000))
        assertEquals(1_000L, recording.then(VideoCaptureMutation.Tick(1_000)).elapsedMs)
        val stopping = recording.then(VideoCaptureMutation.Stopping)
        assertEquals(VideoPhase.STOPPING, stopping.phase)
        assertTrue(stopping.isCapturing)
        assertEquals(stopping, stopping.then(VideoCaptureMutation.Tick(2_000)))
    }

    @Test
    fun `difuminar no es capturar pero ocupa la pantalla, y volver a reposo la libera`() {
        val blurring = VideoCaptureState().then(VideoCaptureMutation.Blurring)
        assertFalse(blurring.isCapturing)
        assertTrue(blurring.isBusy)
        val idle = blurring.then(VideoCaptureMutation.Idle)
        assertFalse(idle.isBusy)
        assertEquals(
            VideoMessage.SAVE_FAILED,
            idle.then(VideoCaptureMutation.Message(VideoMessage.SAVE_FAILED)).message
        )
        assertEquals(CameraPrompt.BLOCKED, idle.then(VideoCaptureMutation.Prompt(CameraPrompt.BLOCKED)).prompt)
    }
}
