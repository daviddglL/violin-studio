package com.violinstudio.ui.feature.guardian.view

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.violinstudio.domain.feature.session.ConsentReason
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.guardian.viewmodel.GuardianEmailError
import com.violinstudio.ui.feature.guardian.viewmodel.GuardianRequestError
import com.violinstudio.ui.feature.guardian.viewmodel.GuardianRequestState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h1200dp-xxhdpi")
class GuardianRequestScreenScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun capture(state: GuardianRequestState, name: String) {
        compose.setContent { ViolinStudioTheme { GuardianRequestScreen(state, onIntent = {}) } }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test fun first() = capture(GuardianRequestState(), "guardian_request_first")

    @Test fun filled() = capture(GuardianRequestState(email = "tutor@example.com"), "guardian_request_filled")

    @Test fun invalid() = capture(
        GuardianRequestState(email = "tutor@", emailError = GuardianEmailError.INVALID),
        "guardian_request_invalid"
    )

    @Test fun rateLimited() = capture(
        GuardianRequestState(
            email = "tutor@example.com",
            error = GuardianRequestError.RATE_LIMITED,
            retryAfterSeconds = 600
        ),
        "guardian_request_rate_limited"
    )

    @Test fun revoked() = capture(GuardianRequestState(reason = ConsentReason.REVOKED), "guardian_request_revoked")

    @Test fun sending() = capture(
        GuardianRequestState(email = "tutor@example.com", isLoading = true),
        "guardian_request_sending"
    )
}
