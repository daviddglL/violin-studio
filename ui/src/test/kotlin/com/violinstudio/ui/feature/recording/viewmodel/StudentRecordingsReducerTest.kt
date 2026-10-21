package com.violinstudio.ui.feature.recording.viewmodel

import com.violinstudio.domain.feature.recording.model.PlaybackState
import com.violinstudio.domain.feature.recording.model.RecordingSummary
import com.violinstudio.domain.feature.recording.model.Review
import com.violinstudio.domain.feature.recording.model.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StudentRecordingsReducerTest {
    private fun StudentRecordingsState.then(mutation: StudentRecordingsMutation) =
        StudentRecordingsReducer.reduce(this, mutation)

    private val r0 = RecordingSummary("r0", 1, 1_000, ready = true, isDemo = false, objectPath = "p")

    @Test
    fun `carga, fallo y reintento`() {
        val loaded = StudentRecordingsState().then(StudentRecordingsMutation.Loaded(listOf(r0)))
        assertFalse(loaded.isLoading)
        assertEquals(listOf(r0), loaded.recordings)
        val failed = loaded.then(StudentRecordingsMutation.EditorOpened("r0"))
            .then(StudentRecordingsMutation.LoadFailed)
        assertTrue(failed.loadFailed)
        assertTrue(failed.recordings.isEmpty())
        assertNull(failed.editor)
        val loading = failed.then(StudentRecordingsMutation.Loading)
        assertTrue(loading.isLoading)
        assertFalse(loading.loadFailed)
        assertEquals(
            PlaybackState.Playing("r0"),
            loading.then(StudentRecordingsMutation.Playback(PlaybackState.Playing("r0"))).playback
        )
    }

    @Test
    fun `el editor carga la valoracion existente una vez y limita el texto`() {
        val open = StudentRecordingsState(recordings = listOf(r0)).then(StudentRecordingsMutation.EditorOpened("r0"))
        assertTrue(open.editor!!.loading)
        assertFalse(open.editor!!.canSave)
        val review = Review(Verdict.NEEDS_WORK, "Arco", 1L)
        assertEquals(open, open.then(StudentRecordingsMutation.EditorLoaded("r9", review)))
        val loaded = open.then(StudentRecordingsMutation.EditorLoaded("r0", review))
        assertEquals(
            ReviewEditor("r0", loading = false, existing = true, verdict = Verdict.NEEDS_WORK, text = "Arco"),
            loaded.editor
        )
        assertTrue(loaded.editor!!.canSave)
        // Una segunda lectura no pisa lo que el profesor ya esta escribiendo.
        assertEquals(loaded, loaded.then(StudentRecordingsMutation.EditorLoaded("r0", null)))
        val fresh = open.then(StudentRecordingsMutation.EditorLoaded("r0", null)).editor!!
        assertFalse(fresh.existing)
        assertNull(fresh.verdict)
        assertFalse(fresh.canSave)
        val edited = loaded.then(StudentRecordingsMutation.VerdictChosen(Verdict.APPROVED))
            .then(StudentRecordingsMutation.Text("a".repeat(Review.MAX_TEXT + 5)))
        assertEquals(Verdict.APPROVED, edited.editor!!.verdict)
        assertEquals(Review.MAX_TEXT, edited.editor!!.text.length)
        val saving = edited.then(StudentRecordingsMutation.Saving(true))
        assertFalse(saving.editor!!.canSave)
        assertNull(saving.then(StudentRecordingsMutation.EditorClosed).editor)
        assertEquals("ReviewEditor(loading=false, verdict=APPROVED, saving=true)", saving.editor.toString())
    }

    @Test
    fun `sin editor los cambios no hacen nada y el editor se cierra si la grabacion desaparece`() {
        val none = StudentRecordingsState(recordings = listOf(r0))
        assertNull(none.then(StudentRecordingsMutation.VerdictChosen(Verdict.APPROVED)).editor)
        assertNull(none.then(StudentRecordingsMutation.Text("x")).editor)
        assertNull(none.then(StudentRecordingsMutation.Saving(true)).editor)
        assertEquals(none, none.then(StudentRecordingsMutation.EditorLoaded("r0", null)))
        val open = none.then(StudentRecordingsMutation.EditorOpened("r0"))
        assertEquals("r0", open.then(StudentRecordingsMutation.Loaded(listOf(r0))).editor?.recordingId)
        assertNull(open.then(StudentRecordingsMutation.Loaded(emptyList())).editor)
        val message = none.then(StudentRecordingsMutation.Message(StudentRecordingsMessage.SAVED))
        assertEquals(StudentRecordingsMessage.SAVED, message.message)
        assertEquals(
            "StudentRecordingsState(isLoading=true, loadFailed=false, recordings=1, editor=false, message=SAVED)",
            message.toString()
        )
    }
}
