package com.violinstudio.ui.feature.settings.view

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.violinstudio.domain.feature.profile.failure.ProfileField
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.settings.viewmodel.RevokeError
import com.violinstudio.ui.feature.settings.viewmodel.SettingsError
import com.violinstudio.ui.feature.settings.viewmodel.SettingsFields
import com.violinstudio.ui.feature.settings.viewmodel.SettingsState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h1200dp-xxhdpi")
class SettingsScreenScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val fields = SettingsFields("Ana", Instrument.VIOLIN, "es-ES")
    private val loaded = SettingsState(fields = fields, baseline = fields)

    private fun capture(state: SettingsState, name: String, withDelete: Boolean = true) {
        compose.setContent {
            ViolinStudioTheme { SettingsScreen(state, {}, {}, if (withDelete) ({}) else null) }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test fun loading() = capture(SettingsState(), "settings_loading")

    @Test fun loaded() = capture(loaded, "settings_loaded")

    @Test fun dirtyWithoutDelete() = capture(
        loaded.copy(fields = fields.copy(displayName = "Ana Maria", instrument = Instrument.CELLO)),
        "settings_dirty",
        withDelete = false
    )

    @Test fun fieldErrors() = capture(
        loaded.copy(
            fields = fields.copy(displayName = " ", locale = "espanol"),
            fieldErrors = setOf(ProfileField.DISPLAY_NAME, ProfileField.LOCALE)
        ),
        "settings_field_errors"
    )

    @Test fun saving() = capture(
        loaded.copy(fields = fields.copy(displayName = "Bea"), isSaving = true),
        "settings_saving"
    )

    @Test fun savedNotice() = capture(loaded.copy(saved = true), "settings_saved")

    @Test fun saveError() = capture(
        loaded.copy(fields = fields.copy(locale = "en"), error = SettingsError.NOT_ALLOWED),
        "settings_save_error"
    )

    @Test fun confirmRevoke() = capture(loaded.copy(confirmingRevoke = true), "settings_confirm_revoke")

    @Test fun revoked() = capture(loaded.copy(revoked = true), "settings_revoked")

    @Test fun revokeError() = capture(loaded.copy(revokeError = RevokeError.NETWORK), "settings_revoke_error")
}
