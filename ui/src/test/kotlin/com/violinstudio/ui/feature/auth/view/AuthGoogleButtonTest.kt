package com.violinstudio.ui.feature.auth.view

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.auth.model.GoogleIdToken
import com.violinstudio.ui.R
import com.violinstudio.ui.commons.auth.FakeGoogleIdTokenRequester
import com.violinstudio.ui.commons.auth.GoogleIdTokenResult
import com.violinstudio.ui.commons.auth.LocalGoogleIdTokenRequester
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.auth.viewmodel.LoginError
import com.violinstudio.ui.feature.auth.viewmodel.LoginIntent
import com.violinstudio.ui.feature.auth.viewmodel.LoginState
import com.violinstudio.ui.feature.auth.viewmodel.RegisterIntent
import com.violinstudio.ui.feature.auth.viewmodel.RegisterState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class AuthGoogleButtonTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val loginIntents = mutableListOf<LoginIntent>()
    private val registerIntents = mutableListOf<RegisterIntent>()

    private fun showLogin(result: GoogleIdTokenResult, state: LoginState = LoginState()): FakeGoogleIdTokenRequester {
        val requester = FakeGoogleIdTokenRequester(result)
        compose.setContent {
            ViolinStudioTheme {
                CompositionLocalProvider(LocalGoogleIdTokenRequester provides requester) {
                    LoginScreen(state, { loginIntents += it }, {}, {})
                }
            }
        }
        return requester
    }

    private fun showRegister(result: GoogleIdTokenResult): FakeGoogleIdTokenRequester {
        val requester = FakeGoogleIdTokenRequester(result)
        compose.setContent {
            ViolinStudioTheme {
                CompositionLocalProvider(LocalGoogleIdTokenRequester provides requester) {
                    RegisterScreen(RegisterState(), { registerIntents += it }, {})
                }
            }
        }
        return requester
    }

    private fun clickGoogle() {
        compose.onNodeWithTag(AUTH_GOOGLE_TAG).performClick()
        compose.waitForIdle()
    }

    @Test
    fun loginOffersContinueWithGoogle() {
        showLogin(GoogleIdTokenResult.Cancelled)
        compose.onNodeWithText(context.getString(R.string.auth_google_button)).assertIsDisplayed()
    }

    @Test
    fun loginDeliversTheTokenAsAnIntent() {
        val requester = showLogin(GoogleIdTokenResult.Token(GoogleIdToken("jwt")))
        clickGoogle()
        assertEquals(1, requester.calls)
        val intent = loginIntents.single() as LoginIntent.GoogleTokenReceived
        assertEquals("jwt", intent.token.value)
    }

    @Test
    fun loginCancellationSendsNothing() {
        val requester = showLogin(GoogleIdTokenResult.Cancelled)
        clickGoogle()
        assertEquals(1, requester.calls)
        assertTrue(loginIntents.isEmpty())
    }

    @Test
    fun loginUnavailableProviderSendsGoogleFailed() {
        showLogin(GoogleIdTokenResult.ProviderUnavailable)
        clickGoogle()
        assertEquals(listOf<LoginIntent>(LoginIntent.GoogleFailed), loginIntents)
    }

    @Test
    fun loginShowsTheGoogleUnavailableMessage() {
        showLogin(GoogleIdTokenResult.Cancelled, LoginState(error = LoginError.GOOGLE_UNAVAILABLE))
        compose.onNodeWithText(context.getString(R.string.auth_google_unavailable)).assertIsDisplayed()
    }

    @Test
    fun loginGoogleButtonIsDisabledWhileLoading() {
        showLogin(GoogleIdTokenResult.Cancelled, LoginState(isLoading = true))
        compose.onNodeWithTag(AUTH_GOOGLE_TAG).assertIsNotEnabled()
    }

    @Test
    fun registerDeliversTokenAndFailureAsIntents() {
        showRegister(GoogleIdTokenResult.Token(GoogleIdToken("jwt")))
        clickGoogle()
        assertEquals("jwt", (registerIntents.single() as RegisterIntent.GoogleTokenReceived).token.value)
    }

    @Test
    fun registerUnavailableProviderSendsGoogleFailed() {
        showRegister(GoogleIdTokenResult.ProviderUnavailable)
        clickGoogle()
        assertEquals(listOf<RegisterIntent>(RegisterIntent.GoogleFailed), registerIntents)
    }
}
