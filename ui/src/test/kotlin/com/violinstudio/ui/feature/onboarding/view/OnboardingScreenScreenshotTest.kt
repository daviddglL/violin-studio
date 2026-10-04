package com.violinstudio.ui.feature.onboarding.view

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.violinstudio.domain.feature.profile.failure.ProfileField
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.onboarding.viewmodel.OnboardingDeleteError
import com.violinstudio.ui.feature.onboarding.viewmodel.OnboardingError
import com.violinstudio.ui.feature.onboarding.viewmodel.OnboardingState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h1200dp-xxhdpi")
class OnboardingScreenScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val filled = OnboardingState(
        displayName = "Ana",
        instrument = Instrument.VIOLIN,
        locale = "es-ES",
        day = "15",
        month = "6",
        year = "1990"
    )

    private fun capture(state: OnboardingState, name: String) {
        compose.setContent { ViolinStudioTheme { OnboardingScreen(state, onIntent = {}) } }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test fun idle() = capture(OnboardingState(), "onboarding_idle")

    @Test fun filled() = capture(filled, "onboarding_filled")

    @Test fun fieldErrors() = capture(
        OnboardingState(
            day = "31",
            month = "2",
            year = "1990",
            fieldErrors = setOf(ProfileField.DISPLAY_NAME, ProfileField.INSTRUMENT, ProfileField.BIRTH_DATE)
        ),
        "onboarding_field_errors"
    )

    @Test fun ageHint() = capture(filled.copy(year = "2020", ageHint = true), "onboarding_age_hint")

    @Test fun underageVerdict() = capture(
        filled.copy(year = "2020", ageHint = true, error = OnboardingError.UNDERAGE_NOT_ALLOWED),
        "onboarding_underage"
    )

    @Test fun deleteNeedsRecentLogin() = capture(
        filled.copy(
            error = OnboardingError.UNDERAGE_NOT_ALLOWED,
            deleteError = OnboardingDeleteError.REAUTH_REQUIRED
        ),
        "onboarding_delete_reauth"
    )

    @Test fun futureDate() = capture(
        filled.copy(year = "2030", birthDateInFuture = true, fieldErrors = setOf(ProfileField.BIRTH_DATE)),
        "onboarding_future_date"
    )
}
