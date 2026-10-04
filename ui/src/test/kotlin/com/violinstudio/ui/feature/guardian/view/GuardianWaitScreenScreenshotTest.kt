package com.violinstudio.ui.feature.guardian.view

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.guardian.viewmodel.GuardianEmailError
import com.violinstudio.ui.feature.guardian.viewmodel.GuardianRequestError
import com.violinstudio.ui.feature.guardian.viewmodel.GuardianWaitNotice
import com.violinstudio.ui.feature.guardian.viewmodel.GuardianWaitState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h1200dp-xxhdpi")
class GuardianWaitScreenScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val waiting = GuardianWaitState(emailMasked = "t***@example.com", sends = 1, canResend = true)

    private fun capture(state: GuardianWaitState, name: String) {
        compose.setContent { ViolinStudioTheme { GuardianWaitScreen(state, onIntent = {}) } }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test fun waiting() = capture(waiting, "guardian_wait_waiting")

    @Test fun withoutResend() = capture(waiting.copy(canResend = false), "guardian_wait_without_resend")

    @Test fun resent() = capture(waiting.copy(notice = GuardianWaitNotice.RESENT), "guardian_wait_resent")

    @Test fun changing() = capture(
        waiting.copy(changingEmail = true, email = "nuevo@example.com"),
        "guardian_wait_changing"
    )

    @Test fun invalid() = capture(
        waiting.copy(changingEmail = true, email = "nuevo@", emailError = GuardianEmailError.INVALID),
        "guardian_wait_invalid"
    )

    @Test fun rateLimited() = capture(
        waiting.copy(resendBlocked = true, error = GuardianRequestError.RATE_LIMITED, retryAfterSeconds = 600),
        "guardian_wait_rate_limited"
    )

    @Test fun alreadyApproved() = capture(
        waiting.copy(notice = GuardianWaitNotice.ALREADY_APPROVED),
        "guardian_wait_already_approved"
    )

    @Test fun sending() = capture(waiting.copy(isLoading = true), "guardian_wait_sending")
}
