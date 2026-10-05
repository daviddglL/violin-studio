package com.violinstudio.ui.feature.tuner.view

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.profile.model.Instrument
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
@Config(application = Application::class)
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
        compose.onNodeWithText("Chromatic mode: any note is detected").assertIsDisplayed()
        compose.onNodeWithTag(tunerStringTag(0)).assertDoesNotExist()
    }

    @Test
    @Config(application = Application::class, qualifiers = "es")
    fun spanishDevicesGetSpanishText() {
        show(TunerState())
        compose.onNodeWithText("Escuchar").assertIsDisplayed()
    }
}
