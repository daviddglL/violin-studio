package com.violinstudio.ui.feature.tuner.view

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.tuner.model.Note
import com.violinstudio.domain.feature.tuner.model.TunerReading
import com.violinstudio.domain.feature.tuner.model.TuningTarget
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.tuner.viewmodel.MicState
import com.violinstudio.ui.feature.tuner.viewmodel.TunerError
import com.violinstudio.ui.feature.tuner.viewmodel.TunerIntent
import com.violinstudio.ui.feature.tuner.viewmodel.TunerState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "es")
class TunerScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val sent = mutableListOf<TunerIntent>()
    private var starts = 0

    private fun show(state: TunerState) {
        compose.setContent {
            ViolinStudioTheme { TunerScreen(state, { sent += it }, onStart = { starts++ }, onBack = {}) }
        }
    }

    @Test
    fun listenAsksTheRouteToStart() {
        show(TunerState(instrument = Instrument.VIOLIN))
        compose.onNodeWithTag(TUNER_LISTEN_TAG).performClick()
        assertEquals(1, starts)
        compose.onNodeWithTag(TUNER_LISTEN_TAG).assertIsDisplayed()
    }

    @Test
    fun whileListeningTheButtonStops() {
        show(TunerState(instrument = Instrument.VIOLIN, isListening = true))
        compose.onNodeWithTag(TUNER_STOP_TAG).performClick()
        assertEquals(listOf<TunerIntent>(TunerIntent.Stop), sent)
    }

    @Test
    fun theRationaleDialogConfirmsOrDismisses() {
        show(TunerState(mic = MicState.DENIED, showRationale = true))
        compose.onNodeWithTag(TUNER_RATIONALE_CONFIRM_TAG).performClick()
        compose.onNodeWithTag(TUNER_RATIONALE_DISMISS_TAG).performClick()
        assertEquals(listOf(TunerIntent.ConfirmRationale, TunerIntent.DismissRationale), sent)
    }

    @Test
    fun aPermanentDenialOffersTheAppSettingsInsteadOfListening() {
        show(TunerState(mic = MicState.PERMANENTLY_DENIED))
        compose.onNodeWithTag(TUNER_LISTEN_TAG).assertDoesNotExist()
        compose.onNodeWithTag(TUNER_SETTINGS_TAG).performClick()
        assertEquals(listOf<TunerIntent>(TunerIntent.OpenAppSettings), sent)
    }

    @Test
    fun aMicErrorOffersARetryThatStartsAgain() {
        show(TunerState(instrument = Instrument.VIOLIN, error = TunerError.MIC_BUSY))
        compose.onNodeWithTag(TUNER_RETRY_TAG).performClick()
        assertEquals(1, starts)
    }

    @Test
    fun theSelectorsStayAvailableWithoutMicrophone() {
        show(TunerState(instrument = Instrument.VIOLIN, error = TunerError.MIC_UNAVAILABLE))
        compose.onNodeWithTag(tunerStringTag(1)).performClick()
        compose.onNodeWithTag(tunerInstrumentTag(Instrument.CELLO.wire)).performClick()
        assertEquals(listOf(TunerIntent.SelectString(1), TunerIntent.SelectInstrument(Instrument.CELLO)), sent)
    }

    @Test
    fun chromaticModeHasNoStringChips() {
        show(TunerState(instrument = Instrument.OTHER))
        compose.onNodeWithText("Modo cromático: se detecta cualquier nota").assertIsDisplayed()
        compose.onNodeWithTag(tunerStringTag(0)).assertDoesNotExist()
    }

    @Test
    @Config(application = Application::class, qualifiers = "en")
    fun englishDevicesGetEnglishText() {
        show(TunerState())
        compose.onNodeWithText("Listen").assertIsDisplayed()
    }

    @Test
    fun aPitchShowsCentsInTuneAndTheWheelDescription() {
        show(TunerState(instrument = Instrument.VIOLIN, reading = pitch(1.0)))
        compose.onNodeWithTag(TUNER_READING_TAG).assertTextEquals("A4  +1 ¢")
        compose.onNodeWithText("En afinación").assertIsDisplayed()
        compose.onNodeWithContentDescription("A4, +1 ¢").assertIsDisplayed()
        compose.onNodeWithText("Fuera de escala").assertDoesNotExist()
    }

    @Test
    fun anOffScalePitchSaysSoInTextAndInTheWheelDescription() {
        show(TunerState(instrument = Instrument.VIOLIN, reading = pitch(-87.0)))
        compose.onNodeWithTag(TUNER_READING_TAG).assertTextEquals("A4  -87 ¢")
        compose.onNodeWithText("Fuera de escala").assertIsDisplayed()
        compose.onNodeWithContentDescription("A4, -87 ¢, fuera de escala").assertIsDisplayed()
        compose.onNodeWithText("En afinación").assertDoesNotExist()
    }

    @Test
    fun theReadingIsNotALiveRegionButTheInTuneMessageIs() {
        show(TunerState(instrument = Instrument.VIOLIN, reading = pitch(1.0)))
        compose.onNodeWithTag(TUNER_READING_TAG)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        compose.onNodeWithText("En afinación").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
    }

    private fun pitch(cents: Double) = TunerReading.Pitch(440.0, TuningTarget.OpenString(Note(69), 2), cents, 0.9)

    @Test
    fun referenceButtonPlaysTheSelectedString() {
        show(TunerState(instrument = Instrument.VIOLIN, selectedString = 2))
        compose.onNodeWithTag(TUNER_REFERENCE_TAG).assertIsEnabled().performClick()
        assertEquals(listOf<TunerIntent>(TunerIntent.ToggleReference), sent)
    }

    @Test
    fun referenceButtonExplainsWhyItIsDisabled() {
        show(TunerState(instrument = Instrument.VIOLIN))
        compose.onNodeWithTag(TUNER_REFERENCE_TAG).assertIsNotEnabled()
        compose.onNodeWithText("Elige una cuerda para oír su tono").assertExists()
    }

    @Test
    fun referenceButtonIsDisabledWhileListeningAndSaysSo() {
        show(TunerState(instrument = Instrument.VIOLIN, selectedString = 2, isListening = true))
        compose.onNodeWithTag(TUNER_REFERENCE_TAG).assertIsNotEnabled()
        compose.onNodeWithText("Detén la escucha para oír el tono").assertExists()
    }

    @Test
    fun whilePlayingTheReferenceButtonOffersToStop() {
        show(TunerState(instrument = Instrument.VIOLIN, selectedString = 2, isPlayingReference = true))
        compose.onNodeWithTag(TUNER_REFERENCE_TAG).assertTextEquals("Parar tono")
    }

    @Test
    fun anOutputFailureIsShownWithoutRetryingTheMic() {
        show(TunerState(instrument = Instrument.VIOLIN, error = TunerError.AUDIO_OUTPUT_UNAVAILABLE))
        compose.onNodeWithText("No se pudo reproducir el tono.").assertIsDisplayed()
        compose.onNodeWithTag(TUNER_RETRY_TAG).assertDoesNotExist()
    }
}
