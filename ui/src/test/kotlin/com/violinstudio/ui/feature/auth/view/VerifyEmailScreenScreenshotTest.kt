package com.violinstudio.ui.feature.auth.view

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.auth.viewmodel.VerifyEmailMessage
import com.violinstudio.ui.feature.auth.viewmodel.VerifyEmailState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class VerifyEmailScreenScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun capture(state: VerifyEmailState, name: String) {
        compose.setContent {
            ViolinStudioTheme { VerifyEmailScreen(email = "ana@example.test", state = state, onIntent = {}) }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test fun idle() = capture(VerifyEmailState(), "verify_email_idle")

    @Test fun stillUnverified() = capture(
        VerifyEmailState(message = VerifyEmailMessage.NOT_VERIFIED_YET),
        "verify_email_still_unverified"
    )

    @Test fun resendCooldown() = capture(
        VerifyEmailState(resendCooldownSeconds = 42, message = VerifyEmailMessage.RESEND_SENT),
        "verify_email_resend_cooldown"
    )

    @Test fun tooManyRequests() = capture(
        VerifyEmailState(resendCooldownSeconds = 60, message = VerifyEmailMessage.WAIT_TOO_MANY_REQUESTS),
        "verify_email_too_many_requests"
    )

    @Test fun checking() = capture(VerifyEmailState(checking = true), "verify_email_checking")
}
