package com.violinstudio.ui.feature.recording.viewmodel

import com.violinstudio.domain.feature.recording.model.PlaybackState
import com.violinstudio.domain.feature.recording.model.RecordingAccess
import com.violinstudio.domain.feature.recording.model.RecordingSummary
import com.violinstudio.domain.feature.recording.model.RecordingsOverview
import com.violinstudio.domain.feature.recording.model.Review
import com.violinstudio.domain.feature.recording.model.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecordingsReducerTest {
    private fun RecordingsState.then(mutation: RecordingsMutation) = RecordingsReducer.reduce(this, mutation)

    @Test
    fun `acceso cargando, cargado y fallido`() {
        val failed = RecordingsState().then(RecordingsMutation.AccessFailed)
        assertTrue(failed.accessFailed)
        val loading = failed.then(RecordingsMutation.AccessLoading)
        assertFalse(loading.accessFailed)
        assertNull(loading.access)
        assertEquals(
            RecordingAccess.Disabled,
            loading.then(RecordingsMutation.AccessLoaded(RecordingAccess.Disabled)).access
        )
    }

    @Test
    fun `grabar empieza en cero, cuenta solo mientras graba y limpia avisos`() {
        val state = RecordingsState(mic = MicPrompt.RATIONALE, message = RecordingsMessage.SAVED)
        assertNull(state.then(RecordingsMutation.Tick(500)).elapsedMs)
        val recording = state.then(RecordingsMutation.RecordingStarted)
        assertEquals(0L, recording.elapsedMs)
        assertNull(recording.mic)
        assertNull(recording.message)
        assertEquals(500L, recording.then(RecordingsMutation.Tick(500)).elapsedMs)
        assertFalse(recording.then(RecordingsMutation.RecordingEnded).isRecording)
    }

    @Test
    fun `dialogos, ocupado, permiso, vista general y mensajes`() {
        val state = RecordingsState()
            .then(RecordingsMutation.Busy(true))
            .then(RecordingsMutation.RevokePrompt(true))
            .then(RecordingsMutation.DeletePrompt("r1"))
            .then(RecordingsMutation.Mic(MicPrompt.BLOCKED))
            .then(RecordingsMutation.Overview(RecordingsOverview("u1", emptyList(), emptyList())))
            .then(RecordingsMutation.Message(RecordingsMessage.OFFLINE))
        assertTrue(state.busy && state.confirmRevoke)
        assertEquals("r1", state.confirmDeleteId)
        assertEquals(MicPrompt.BLOCKED, state.mic)
        assertEquals("u1", state.uid)
        assertEquals(RecordingsMessage.OFFLINE, state.message)
        assertTrue("u1" !in state.toString())
    }

    @Test
    fun `reproductor y valoraciones solo de la grabacion abierta`() {
        val review = Review(Verdict.APPROVED, "", null)
        val playing = RecordingsState().then(RecordingsMutation.Playback(PlaybackState.Loading("r0")))
        assertEquals(PlaybackState.Loading("r0"), playing.playback)
        val open = playing.then(RecordingsMutation.ReviewsOpened("r0"))
        assertEquals("r0", open.reviewsFor)
        assertNull(open.reviews)
        assertEquals(open, open.then(RecordingsMutation.ReviewsLoaded("r9", listOf(review))))
        assertEquals(open, open.then(RecordingsMutation.ReviewsFailed("r9")))
        val loaded = open.then(RecordingsMutation.ReviewsLoaded("r0", listOf(review)))
        assertEquals(listOf(review), loaded.reviews)
        val failed = loaded.then(RecordingsMutation.ReviewsFailed("r0"))
        assertTrue(failed.reviewsFailed)
        assertNull(failed.reviews)
        val kept = loaded.then(
            RecordingsMutation.Overview(
                RecordingsOverview("u1", emptyList(), listOf(RecordingSummary("r0", 1, 1, true, false)))
            )
        )
        assertEquals(listOf(review), kept.reviews)
        val gone = kept.then(RecordingsMutation.Overview(RecordingsOverview("u1", emptyList(), emptyList())))
        assertNull(gone.reviewsFor)
        assertNull(gone.reviews)
        assertNull(loaded.then(RecordingsMutation.ReviewsOpened(null)).reviewsFor)
    }
}
