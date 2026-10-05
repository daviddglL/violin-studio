package com.violinstudio.ui.feature.tuner.view

import android.app.Application
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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

    private var state by mutableStateOf(TunerState())

    private fun content() = @Composable { ViolinStudioTheme { TunerScreen(state, { sent += it }, {}, {}) } }

    /** Solo el contenido: el diálogo del ModalBottomSheet no participa del registro de estado del tester. */
    private fun contentOnly() = @Composable {
        ViolinStudioTheme { Surface { TunerConfigContent(state.config, state.configError) { sent += it } } }
    }

    private fun show(initial: TunerState) {
        state = initial
        compose.setContent(content())
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
    fun everyConfigErrorShowsItsOwnMessage() {
        open()
        val messages = mapOf(
            ConfigError.REFERENCE_PITCH to "Introduce un valor entre 415 y 466 Hz",
            ConfigError.MAX_CENTS to "Introduce un valor entre 25 y 200 cents",
            ConfigError.LABEL to "El nombre debe tener entre 1 y 30 caracteres",
            ConfigError.DUPLICATE_LABEL to "Ya tienes un preset con ese nombre",
            ConfigError.PRESET_LIMIT to "Has alcanzado el máximo de 20 presets",
            ConfigError.PRESET_NOT_FOUND to "Ese preset ya no existe",
            ConfigError.STORAGE to "No se pudo guardar en el dispositivo",
            ConfigError.NO_SESSION to "Tu sesión ha caducado. Vuelve a iniciar sesión",
            ConfigError.UNKNOWN to "No se pudo guardar la configuración"
        )
        messages.forEach { (error, text) ->
            state = state.copy(configError = error)
            compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun editingAFieldAsksToClearTheErrorOnlyWhenThereIsOne() {
        open()
        compose.onNodeWithTag(TUNER_CONFIG_HZ_TAG).performTextReplacement("441")
        assertTrue(sent.isEmpty())
        state = state.copy(configError = ConfigError.REFERENCE_PITCH)
        compose.onNodeWithTag(TUNER_CONFIG_HZ_TAG).performTextReplacement("442")
        assertEquals(listOf<TunerIntent>(TunerIntent.ClearConfigError), sent)
    }

    @Test
    fun numericInputsAreTrimmed() {
        open()
        compose.onNodeWithTag(TUNER_CONFIG_HZ_TAG).performTextReplacement(" 442 ")
        compose.onNodeWithTag(TUNER_CONFIG_CENTS_TAG).performTextReplacement(" 75 ")
        compose.onNodeWithTag(TUNER_CONFIG_APPLY_TAG).performClick()
        assertEquals(listOf<TunerIntent>(TunerIntent.UpdateConfig(442.0, 75)), sent)
    }

    @Test
    fun presetButtonsNameThePresetForScreenReaders() {
        open(TunerConfig(presets = listOf(baroque)))
        compose.onNodeWithContentDescription("Usar Barroco").assertExists()
        compose.onNodeWithContentDescription("Editar Barroco").assertExists()
        compose.onNodeWithContentDescription("Borrar Barroco").assertExists()
    }

    @Test
    fun cancellingAnEditClearsTheFormAndTheNextSaveCreatesANewPreset() {
        open(TunerConfig(presets = listOf(baroque)))
        compose.onNodeWithTag(tunerPresetEditTag("b1")).performScrollTo().performClick()
        compose.onNodeWithTag(TUNER_CONFIG_CANCEL_EDIT_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(TUNER_CONFIG_CANCEL_EDIT_TAG).assertDoesNotExist()
        compose.onNodeWithTag(TUNER_CONFIG_LABEL_TAG).performScrollTo().performTextReplacement("Nuevo")
        compose.onNodeWithTag(TUNER_CONFIG_SAVE_TAG).performScrollTo().performClick()
        assertEquals(listOf<TunerIntent>(TunerIntent.SavePreset(null, "Nuevo", 440.0, 50)), sent)
    }

    @Test
    fun theEditFormSurvivesRotationSoSaveStillEditsThePreset() {
        val restoration = StateRestorationTester(compose)
        state = TunerState(config = TunerConfig(presets = listOf(baroque)), showConfig = true)
        restoration.setContent(contentOnly())
        compose.onNodeWithTag(tunerPresetEditTag("b1")).performScrollTo().performClick()
        compose.onNodeWithTag(TUNER_CONFIG_CENTS_TAG).performTextReplacement("90")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag(TUNER_CONFIG_SAVE_TAG).performScrollTo().performClick()
        assertEquals(listOf<TunerIntent>(TunerIntent.SavePreset("b1", "Barroco", 415.0, 90)), sent)
    }

    @Test
    fun theDeleteConfirmationSurvivesRotation() {
        val restoration = StateRestorationTester(compose)
        state = TunerState(config = TunerConfig(presets = listOf(baroque)), showConfig = true)
        restoration.setContent(contentOnly())
        compose.onNodeWithTag(tunerPresetDeleteTag("b1")).performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag(TUNER_CONFIG_DELETE_CONFIRM_TAG).assertIsDisplayed()
    }

    @Test
    fun selectingAPresetDropsStaleTypedValuesAndEditState() {
        val other = TuningConfiguration("c1", "Orquesta", ReferencePitch(442.0), MaxCents(100))
        open(TunerConfig(presets = listOf(baroque, other)))
        compose.onNodeWithTag(tunerPresetEditTag("b1")).performScrollTo().performClick()
        compose.onNodeWithTag(TUNER_CONFIG_HZ_TAG).performTextReplacement("460")
        state = state.copy(
            config = TunerConfig(ReferencePitch(442.0), MaxCents(100), listOf(baroque, other), "c1")
        )
        compose.onNodeWithTag(TUNER_CONFIG_HZ_TAG).assert(hasText("442"))
        compose.onNodeWithTag(TUNER_CONFIG_CANCEL_EDIT_TAG).assertDoesNotExist()
    }

    @Test
    fun deletingThePresetBeingEditedResetsTheForm() {
        open(TunerConfig(presets = listOf(baroque)))
        compose.onNodeWithTag(tunerPresetEditTag("b1")).performScrollTo().performClick()
        state = state.copy(config = TunerConfig())
        compose.onNodeWithTag(TUNER_CONFIG_CANCEL_EDIT_TAG).assertDoesNotExist()
        compose.onNodeWithTag(TUNER_CONFIG_HZ_TAG).assert(hasText("440"))
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
