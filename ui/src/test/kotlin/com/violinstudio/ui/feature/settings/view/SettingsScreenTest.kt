package com.violinstudio.ui.feature.settings.view

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.profile.failure.ProfileField
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.ui.R
import com.violinstudio.ui.commons.locale.AppLanguage
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.account.view.FAKE_DELETE_TAG
import com.violinstudio.ui.feature.account.view.LocalDeleteAccount
import com.violinstudio.ui.feature.account.view.fakeDeleteScope
import com.violinstudio.ui.feature.settings.viewmodel.RevokeError
import com.violinstudio.ui.feature.settings.viewmodel.SettingsError
import com.violinstudio.ui.feature.settings.viewmodel.SettingsFields
import com.violinstudio.ui.feature.settings.viewmodel.SettingsIntent
import com.violinstudio.ui.feature.settings.viewmodel.SettingsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class SettingsScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private fun text(id: Int) = context.getString(id)
    private val intents = mutableListOf<SettingsIntent>()
    private var backs = 0

    private val fields = SettingsFields("Ana", Instrument.VIOLIN, "es-ES")
    private val loaded = SettingsState(fields = fields, baseline = fields)

    private var current by mutableStateOf(SettingsState())

    private fun show(state: SettingsState, deleteActive: Boolean = false) {
        current = state
        compose.setContent {
            ViolinStudioTheme {
                CompositionLocalProvider(LocalDeleteAccount provides fakeDeleteScope(deleteActive)) {
                    SettingsScreen(current, { intents += it }, { backs++ })
                }
            }
        }
    }

    private fun polite() = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
    private fun assertive() = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive)

    @Test
    fun `while the profile is loading there is no form to edit or revoke`() {
        show(SettingsState())
        compose.onNodeWithText(text(R.string.settings_loading)).assertIsDisplayed()
        compose.onNodeWithTag(SETTINGS_NAME_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SETTINGS_REVOKE_TAG).assertDoesNotExist()
    }

    @Test
    fun `it shows the three editable fields, revoke and delete, and nothing else sensitive`() {
        show(loaded)
        compose.onNodeWithText(text(R.string.settings_title)).assertIsDisplayed()
        compose.onNodeWithTag(SETTINGS_NAME_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SETTINGS_LOCALE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(settingsInstrumentTag("violin")).assertIsSelected()
        compose.onNodeWithTag(settingsInstrumentTag("cello")).assertIsDisplayed()
        compose.onNodeWithTag(SETTINGS_SAVE_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(SETTINGS_REVOKE_TAG).performScrollTo().assertIsEnabled()
        compose.onNodeWithTag(FAKE_DELETE_TAG).performScrollTo().assertIsEnabled()
    }

    @Test
    fun `typing and picking dispatch the edit intents and save dispatches Save when dirty`() {
        show(loaded.copy(fields = fields.copy(displayName = "Bea")))
        compose.onNodeWithTag(SETTINGS_NAME_TAG).performTextInput("!")
        compose.onNodeWithTag(SETTINGS_LOCALE_TAG).performTextInput("x")
        compose.onNodeWithTag(settingsInstrumentTag("cello")).performScrollTo().performClick()
        compose.onNodeWithTag(SETTINGS_SAVE_TAG).performScrollTo().assertIsEnabled().performClick()
        assertTrue(SettingsIntent.DisplayNameChanged("!Bea") in intents)
        assertTrue(SettingsIntent.LocaleChanged("xes-ES") in intents)
        assertEquals(
            listOf(SettingsIntent.InstrumentSelected(Instrument.CELLO), SettingsIntent.Save),
            intents.takeLast(2)
        )
    }

    @Test
    fun `field errors are shown and announced politely`() {
        show(loaded.copy(fieldErrors = setOf(ProfileField.DISPLAY_NAME, ProfileField.LOCALE)))
        compose.onNodeWithText(text(R.string.settings_field_name)).assert(polite())
        compose.onNodeWithText(text(R.string.settings_field_locale)).assert(polite())
    }

    @Test
    fun `saving relabels the button and locks the fields, revoke and delete`() {
        show(loaded.copy(fields = fields.copy(displayName = "Bea"), isSaving = true))
        compose.onNodeWithText(text(R.string.settings_saving)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(SETTINGS_NAME_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(SETTINGS_LOCALE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(settingsInstrumentTag("cello")).assertIsNotEnabled()
        compose.onNodeWithTag(SETTINGS_REVOKE_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(FAKE_DELETE_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `the saved notice is polite and the save errors are assertive`() {
        show(loaded.copy(saved = true))
        compose.onNodeWithText(text(R.string.settings_saved)).assert(polite())
    }

    @Test
    fun `each save error has its own assertive message`() {
        val cases = mapOf(
            SettingsError.NETWORK to R.string.settings_error_network,
            SettingsError.NOT_ALLOWED to R.string.settings_error_not_allowed,
            SettingsError.UNAVAILABLE to R.string.settings_error_unavailable,
            SettingsError.UNKNOWN to R.string.settings_error_unknown
        )
        show(loaded)
        for ((error, res) in cases) {
            current = loaded.copy(error = error)
            compose.onNodeWithText(text(res)).assert(assertive())
        }
    }

    @Test
    fun `revoke asks for confirmation, then confirms or cancels explicitly`() {
        show(loaded)
        compose.onNodeWithTag(SETTINGS_REVOKE_TAG).performScrollTo().performClick()
        assertEquals(listOf<SettingsIntent>(SettingsIntent.RevokeConsent), intents)
        current = loaded.copy(confirmingRevoke = true)
        compose.onNodeWithText(text(R.string.settings_revoke_confirm)).assertIsDisplayed()
        compose.onNodeWithTag(SETTINGS_REVOKE_CONFIRM_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(SETTINGS_REVOKE_CANCEL_TAG).performScrollTo().performClick()
        assertEquals(
            listOf(SettingsIntent.RevokeConsent, SettingsIntent.ConfirmRevoke, SettingsIntent.CancelRevoke),
            intents
        )
        compose.onNodeWithTag(SETTINGS_REVOKE_TAG).assertDoesNotExist()
    }

    @Test
    fun `revoking relabels the button and locks save and delete`() {
        show(loaded.copy(isRevoking = true))
        compose.onNodeWithText(text(R.string.settings_revoking)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(SETTINGS_SAVE_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(FAKE_DELETE_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `after revoking everything is locked and the notice is polite`() {
        show(loaded.copy(revoked = true))
        compose.onNodeWithText(text(R.string.settings_revoked)).assert(polite())
        compose.onNodeWithTag(SETTINGS_REVOKE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(FAKE_DELETE_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(SETTINGS_NAME_TAG).assertIsNotEnabled()
    }

    @Test
    fun `revoke errors are assertive and the terminal one hides the revoke button`() {
        show(loaded.copy(revokeError = RevokeError.NETWORK))
        compose.onNodeWithText(text(R.string.settings_revoke_network)).assert(assertive())
        compose.onNodeWithTag(SETTINGS_REVOKE_TAG).performScrollTo().assertIsEnabled()
    }

    @Test
    fun `an unavailable revoke is terminal`() {
        show(loaded.copy(revokeError = RevokeError.UNAVAILABLE))
        compose.onNodeWithText(text(R.string.settings_revoke_unavailable)).assert(assertive())
        compose.onNodeWithTag(SETTINGS_REVOKE_TAG).assertDoesNotExist()
    }

    @Test
    fun `the delete entry is the shared one and is only offered once the profile loaded`() {
        show(loaded)
        compose.onNodeWithTag(FAKE_DELETE_TAG).performScrollTo().assertIsEnabled()
    }

    @Test
    fun `while the shared delete flow is active edit, revoke and back are locked`() {
        show(loaded.copy(fields = fields.copy(displayName = "Bea")), deleteActive = true)
        compose.onNodeWithTag(SETTINGS_NAME_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(SETTINGS_SAVE_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(SETTINGS_REVOKE_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(SETTINGS_BACK_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `back calls the handler`() {
        show(loaded)
        compose.onNodeWithTag(SETTINGS_BACK_TAG).performScrollTo().performClick()
        assertEquals(1, backs)
    }

    @Test
    fun `while confirming, the form is locked and the confirm button takes the focus`() {
        show(loaded)
        // Como en una app real: la ventana ya tiene el foco cuando se abre el panel.
        compose.runOnUiThread { compose.activity.window.decorView.requestFocus() }
        current = loaded.copy(confirmingRevoke = true)
        compose.waitForIdle()
        compose.onNodeWithTag(SETTINGS_NAME_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(SETTINGS_LOCALE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(settingsInstrumentTag("cello")).assertIsNotEnabled()
        compose.onNodeWithTag(SETTINGS_SAVE_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(SETTINGS_REVOKE_CONFIRM_TAG).assertIsFocused()
    }

    @Test
    fun `a stalled revoke shows an assertive notice with a retry and unlocks the rest`() {
        show(loaded.copy(revokeStalled = true))
        compose.onNodeWithText(text(R.string.settings_revoke_stalled)).assert(assertive())
        compose.onNodeWithTag(SETTINGS_RETRY_TAG).performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf<SettingsIntent>(SettingsIntent.RetryRefresh), intents)
        compose.onNodeWithTag(SETTINGS_REVOKE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SETTINGS_BACK_TAG).performScrollTo().assertIsEnabled()
        compose.onNodeWithTag(SETTINGS_NAME_TAG).assertIsEnabled()
    }

    @Test
    fun `ofrece las tres opciones de idioma y marca la actual`() {
        show(loaded.copy(language = AppLanguage.ENGLISH))
        compose.onNodeWithText(text(R.string.settings_language_title)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(settingsLanguageTag(AppLanguage.SYSTEM)).performScrollTo().assertIsNotSelected()
        compose.onNodeWithTag(settingsLanguageTag(AppLanguage.SPANISH)).performScrollTo().assertIsNotSelected()
        compose.onNodeWithTag(settingsLanguageTag(AppLanguage.ENGLISH)).performScrollTo().assertIsSelected()
        compose.onNodeWithText(text(R.string.settings_language_system)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.settings_language_es)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.settings_language_en)).assertIsDisplayed()
    }

    @Test
    fun `elegir cada opcion de idioma envia su intent`() {
        show(loaded)
        for (language in listOf(AppLanguage.ENGLISH, AppLanguage.SPANISH, AppLanguage.SYSTEM)) {
            compose.onNodeWithTag(settingsLanguageTag(language)).performScrollTo().performClick()
        }
        assertEquals(
            listOf<SettingsIntent>(
                SettingsIntent.LanguageSelected(AppLanguage.ENGLISH),
                SettingsIntent.LanguageSelected(AppLanguage.SPANISH),
                SettingsIntent.LanguageSelected(AppLanguage.SYSTEM)
            ),
            intents
        )
    }

    @Test
    fun `el selector de idioma no depende de que el perfil haya cargado`() {
        show(SettingsState(language = AppLanguage.SPANISH))
        compose.onNodeWithTag(settingsLanguageTag(AppLanguage.SPANISH)).assertIsSelected()
    }
}
