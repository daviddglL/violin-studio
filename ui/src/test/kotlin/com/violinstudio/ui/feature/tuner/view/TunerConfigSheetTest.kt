package com.violinstudio.ui.feature.tuner.view

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.tuner.model.MaxCents
import com.violinstudio.domain.feature.tuner.model.ReferencePitch
import com.violinstudio.domain.feature.tuner.model.TunerConfig
import com.violinstudio.domain.feature.tuner.model.TuningConfiguration
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.tuner.viewmodel.ConfigError
import com.violinstudio.ui.feature.tuner.viewmodel.TunerIntent
import com.violinstudio.ui.feature.tuner.viewmodel.TunerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "es-w411dp-h1200dp-xxhdpi")
class TunerConfigSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val sent = mutableListOf<TunerIntent>()
    private val baroque = TuningConfiguration("b1", "Barroco", ReferencePitch(415.0), MaxCents(50))

    private fun show(state: TunerState) {
        compose.setContent { ViolinStudioTheme { TunerScreen(state, { sent += it }, {}, {}) } }
    }

    private fun open(config: TunerConfig = TunerConfig(), error: ConfigError? = null) =
        show(TunerState(config = config, showConfig = true, configError = error))

    @Test
    fun theConfigButtonOpensTheSheetAndItStaysClosedByDefault() {
        show(TunerState())
        compose.onNodeWithTag(TUNER_CONFIG_APPLY_TAG).assertDoesNotExist()
        compose.onNodeWithTag(TUNER_CONFIG_OPEN_TAG).performClick()
        assertEquals(listOf<TunerIntent>(TunerIntent.OpenConfig), sent)
    }

    @Test
    fun applyingSendsTheTypedValuesAcceptingADecimalComma() {
        open()
        compose.onNodeWithTag(TUNER_CONFIG_HZ_TAG).performTextReplacement("442,5")
        compose.onNodeWithTag(TUNER_CONFIG_CENTS_TAG).performTextReplacement("100")
        compose.onNodeWithTag(TUNER_CONFIG_APPLY_TAG).performClick()
        assertEquals(listOf<TunerIntent>(TunerIntent.UpdateConfig(442.5, 100)), sent)
    }

    @Test
    fun textThatIsNotANumberIsSentAsInvalidSoTheUseCaseRejectsIt() {
        open()
        compose.onNodeWithTag(TUNER_CONFIG_HZ_TAG).performTextReplacement("abc")
        compose.onNodeWithTag(TUNER_CONFIG_APPLY_TAG).performClick()
        val intent = sent.single() as TunerIntent.UpdateConfig
        assertTrue(intent.referenceHz.isNaN())
    }

    @Test
    fun cancellingSendsCloseAndNothingElse() {
        open()
        compose.onNodeWithTag(TUNER_CONFIG_HZ_TAG).performTextReplacement("442")
        compose.onNodeWithTag(TUNER_CONFIG_CANCEL_TAG).performClick()
        assertEquals(listOf<TunerIntent>(TunerIntent.CloseConfig), sent)
    }

    @Test
    fun eachFieldShowsItsOwnInlineError() {
        open(error = ConfigError.REFERENCE_PITCH)
        compose.onNodeWithText("Introduce un valor entre 415 y 466 Hz").assertIsDisplayed()
    }

    @Test
    fun presetLimitAndStorageFailuresGiveFeedback() {
        open(error = ConfigError.PRESET_LIMIT)
        compose.onNodeWithText("Has alcanzado el máximo de 20 presets").assertIsDisplayed()
    }

    @Test
    fun savingANewPresetSendsItWithoutId() {
        open(TunerConfig(presets = listOf(baroque)))
        compose.onNodeWithTag(TUNER_CONFIG_LABEL_TAG).performScrollTo().performTextReplacement("Clásico")
        compose.onNodeWithTag(TUNER_CONFIG_SAVE_TAG).performScrollTo().performClick()
        assertEquals(listOf<TunerIntent>(TunerIntent.SavePreset(null, "Clásico", 440.0, 50)), sent)
    }

    @Test
    fun editingAPresetLoadsItAndSavesItKeepingItsId() {
        open(TunerConfig(presets = listOf(baroque)))
        compose.onNodeWithTag(tunerPresetEditTag("b1")).performScrollTo().performClick()
        compose.onNodeWithTag(TUNER_CONFIG_CENTS_TAG).performTextReplacement("80")
        compose.onNodeWithTag(TUNER_CONFIG_SAVE_TAG).performScrollTo().performClick()
        assertEquals(listOf<TunerIntent>(TunerIntent.SavePreset("b1", "Barroco", 415.0, 80)), sent)
    }

    @Test
    fun selectingAPresetSendsItsId() {
        open(TunerConfig(presets = listOf(baroque)))
        compose.onNodeWithTag(tunerPresetSelectTag("b1")).performScrollTo().performClick()
        assertEquals(listOf<TunerIntent>(TunerIntent.SelectPreset("b1")), sent)
    }

    @Test
    fun deletingAPresetNeedsConfirmation() {
        open(TunerConfig(presets = listOf(baroque)))
        compose.onNodeWithTag(tunerPresetDeleteTag("b1")).performScrollTo().performClick()
        assertTrue(sent.isEmpty())
        compose.onNodeWithTag(TUNER_CONFIG_DELETE_CONFIRM_TAG).performClick()
        assertEquals(listOf<TunerIntent>(TunerIntent.DeletePreset("b1")), sent)
    }
}
