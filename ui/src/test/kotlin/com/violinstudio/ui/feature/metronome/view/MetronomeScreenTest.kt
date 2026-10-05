package com.violinstudio.ui.feature.metronome.view

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.metronome.model.BeatTick
import com.violinstudio.domain.feature.metronome.model.TimeSignature
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.metronome.viewmodel.MetronomeError
import com.violinstudio.ui.feature.metronome.viewmodel.MetronomeIntent
import com.violinstudio.ui.feature.metronome.viewmodel.MetronomeState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "es")
class MetronomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val sent = mutableListOf<MetronomeIntent>()

    private fun show(state: MetronomeState) {
        compose.setContent { ViolinStudioTheme { MetronomeScreen(state, { sent += it }, onBack = {}) } }
    }

    private fun assertLit(index: Int) = compose.onNodeWithTag(metronomeBeatTag(index))
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))

    @Test
    fun theTempoControlsSendTheirIntents() {
        show(MetronomeState())
        compose.onNodeWithTag(METRONOME_BPM_TAG).assertTextEquals("100 BPM")
        compose.onNodeWithTag(METRONOME_INCREASE_TAG).performClick()
        compose.onNodeWithTag(METRONOME_DECREASE_TAG).performClick()
        compose.onNodeWithTag(METRONOME_TAP_TAG).performClick()
        compose.onNodeWithTag(METRONOME_SLIDER_TAG).performSemanticsAction(SemanticsActions.SetProgress) { it(140f) }
        assertEquals(
            listOf(
                MetronomeIntent.Increment,
                MetronomeIntent.Decrement,
                MetronomeIntent.Tap,
                MetronomeIntent.SetBpm(140)
            ),
            sent
        )
    }

    @Test
    fun theToggleAndTheSignatureSendTheirIntents() {
        show(MetronomeState())
        compose.onNodeWithTag(METRONOME_TOGGLE_TAG).performClick()
        compose.onNodeWithTag(metronomeSignatureTag(TimeSignature.SIX_EIGHT)).performClick()
        assertEquals(listOf(MetronomeIntent.Toggle, MetronomeIntent.SetSignature(TimeSignature.SIX_EIGHT)), sent)
    }

    @Test
    fun oneDotPerBeatWithTheCurrentOneSelected() {
        show(MetronomeState(signature = TimeSignature.SIX_EIGHT, isPlaying = true, tick = BeatTick(8, 2, false)))
        compose.onNodeWithTag(metronomeBeatTag(5)).assertIsDisplayed()
        assertLit(2)
        compose.onNodeWithText("En 6/8 el tempo cuenta corcheas.").assertIsDisplayed()
    }

    @Test
    fun theErrorOffersARetryThatTogglesAgain() {
        show(MetronomeState(error = MetronomeError.OUTPUT_UNAVAILABLE))
        compose.onNodeWithText("No se pudo reproducir el metrónomo.").assertIsDisplayed()
        compose.onNodeWithTag(METRONOME_TOGGLE_TAG).assertTextEquals("Reintentar").performClick()
        assertEquals(listOf(MetronomeIntent.Toggle), sent)
    }

    @Test
    @Config(qualifiers = "en")
    fun englishLabels() {
        show(MetronomeState(isPlaying = true))
        compose.onNodeWithTag(METRONOME_TOGGLE_TAG).assertTextEquals("Stop")
        compose.onNodeWithTag(METRONOME_TAP_TAG).assertTextEquals("Tap tempo")
    }
}
