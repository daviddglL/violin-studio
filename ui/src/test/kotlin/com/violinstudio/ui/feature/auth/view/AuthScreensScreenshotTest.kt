package com.violinstudio.ui.feature.auth.view

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.auth.viewmodel.LoginError
import com.violinstudio.ui.feature.auth.viewmodel.LoginState
import com.violinstudio.ui.feature.auth.viewmodel.RegisterError
import com.violinstudio.ui.feature.auth.viewmodel.RegisterFieldError
import com.violinstudio.ui.feature.auth.viewmodel.RegisterState
import com.violinstudio.ui.feature.auth.viewmodel.ResetPasswordState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class AuthScreensScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String, content: @Composable () -> Unit) {
        compose.setContent { ViolinStudioTheme { content() } }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private fun login(state: LoginState) = @Composable { LoginScreen(state, {}, {}, {}) }

    private fun register(state: RegisterState) = @Composable { RegisterScreen(state, {}, {}) }

    @Test fun loginIdle() = capture("login_idle", login(LoginState()))

    @Test fun loginInvalidCredentials() = capture(
        "login_invalid_credentials",
        login(LoginState(email = "ana@example.test", password = "secret", error = LoginError.INVALID_CREDENTIALS))
    )

    @Test fun loginLoading() = capture(
        "login_loading",
        login(LoginState(email = "ana@example.test", password = "secret", isLoading = true))
    )

    @Test fun loginGoogleOtherProvider() = capture(
        "login_google_other_provider",
        login(LoginState(email = "ana@example.test", error = LoginError.ACCOUNT_EXISTS_OTHER_PROVIDER))
    )

    @Test fun registerGoogleUnavailable() = capture(
        "register_google_unavailable",
        register(RegisterState(error = RegisterError.GOOGLE_UNAVAILABLE))
    )

    @Test fun registerWeakPassword() = capture(
        "register_weak_password",
        register(
            RegisterState(
                email = "ana@example.test",
                password = "123456",
                passwordError = RegisterFieldError.PASSWORD_WEAK
            )
        )
    )

    @Test fun registerAccountUnavailable() = capture(
        "register_account_unavailable",
        register(
            RegisterState(email = "ana@example.test", password = "secret1", error = RegisterError.ACCOUNT_UNAVAILABLE)
        )
    )

    @Test fun resetSent() = capture(
        "reset_sent",
        { ResetPasswordScreen(ResetPasswordState(email = "ana@example.test", sent = true), {}, {}) }
    )
}
