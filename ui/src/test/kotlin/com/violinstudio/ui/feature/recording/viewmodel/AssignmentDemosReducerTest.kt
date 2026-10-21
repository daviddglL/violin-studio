package com.violinstudio.ui.feature.recording.viewmodel

import com.violinstudio.domain.feature.recording.model.PlaybackState
import com.violinstudio.domain.feature.recording.model.RecordingSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AssignmentDemosReducerTest {
    private fun AssignmentDemosState.then(mutation: AssignmentDemosMutation) =
        AssignmentDemosReducer.reduce(this, mutation)

    private val d0 = RecordingSummary("d0", 1, 1_000, ready = true, isDemo = true, objectPath = "p")

    @Test
    fun `carga, fallo, reintento y reproduccion`() {
        val loaded = AssignmentDemosState().then(AssignmentDemosMutation.Loaded(listOf(d0)))
        assertFalse(loaded.isLoading)
        assertEquals(listOf(d0), loaded.demos)
        val failed = loaded.then(AssignmentDemosMutation.LoadFailed)
        assertTrue(failed.loadFailed)
        assertTrue(failed.demos.isEmpty())
        val loading = failed.then(AssignmentDemosMutation.Loading)
        assertTrue(loading.isLoading)
        assertFalse(loading.loadFailed)
        assertEquals(
            PlaybackState.Playing("d0"),
            loading.then(AssignmentDemosMutation.Playback(PlaybackState.Playing("d0"))).playback
        )
    }

    @Test
    fun `el volcado del estado no lleva ids`() {
        assertFalse("d0" in AssignmentDemosState(demos = listOf(d0)).toString())
    }
}
