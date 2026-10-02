package com.violinstudio.ui.feature.auth.view

import android.app.Application
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.autofill.AutofillTree
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.platform.LocalAutofillTree
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.ui.R
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.auth.viewmodel.LoginError
import com.violinstudio.ui.feature.auth.viewmodel.LoginFieldError
import com.violinstudio.ui.feature.auth.viewmodel.LoginIntent
import com.violinstudio.ui.feature.auth.viewmodel.LoginState
import com.violinstudio.ui.feature.auth.viewmodel.RegisterError
import com.violinstudio.ui.feature.auth.viewmodel.RegisterFieldError
import com.violinstudio.ui.feature.auth.viewmodel.RegisterIntent
import com.violinstudio.ui.feature.auth.viewmodel.RegisterState
import com.violinstudio.ui.feature.auth.viewmodel.ResetFieldError
import com.violinstudio.ui.feature.auth.viewmodel.ResetPasswordIntent
import com.violinstudio.ui.feature.auth.viewmodel.ResetPasswordState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@OptIn(ExperimentalComposeUiApi::class)
class AuthScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private fun text(id: Int) = context.getString(id)

    private val loginIntents = mutableListOf<LoginIntent>()
    private val registerIntents = mutableListOf<RegisterIntent>()
    private val resetIntents = mutableListOf<ResetPasswordIntent>()
    private val calls = mutableListOf<String>()
    private lateinit var autofillTree: AutofillTree

    private fun autofillTypes() = autofillTree.children.values.map { it.autofillTypes }

    private fun showLogin(state: LoginState = LoginState()) = compose.setContent {
        ViolinStudioTheme {
            autofillTree = LocalAutofillTree.current
            LoginScreen(state, { loginIntents += it }, { calls += "register" }, { calls += "forgot" })
        }
    }

    private fun showRegister(state: RegisterState = RegisterState()) = compose.setContent {
        ViolinStudioTheme {
            autofillTree = LocalAutofillTree.current
            RegisterScreen(state, { registerIntents += it }, { calls += "back" })
        }
    }

    private fun showReset(state: ResetPasswordState = ResetPasswordState()) = compose.setContent {
        ViolinStudioTheme {
            autofillTree = LocalAutofillTree.current
            ResetPasswordScreen(state, { resetIntents += it }, { calls += "back" })
        }
    }

    private fun liveRegion(mode: LiveRegionMode) = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, mode)

    @Test
    fun loginShowsMaskedPasswordAndSendsIntents() {
        showLogin()
        compose.onNodeWithTag(LOGIN_TAG).assertIsDisplayed()
        compose.onNodeWithTag(AUTH_PASSWORD_TAG)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithTag(AUTH_EMAIL_TAG).performTextInput("ana@example.test")
        compose.onNodeWithTag(AUTH_PASSWORD_TAG).performTextInput("secret")
        compose.onNodeWithTag(AUTH_SUBMIT_TAG).performClick()
        // The state is not fed back in this test, so the controlled field may also report a revert to "".
        assertTrue(loginIntents.contains(LoginIntent.EmailChanged("ana@example.test")))
        assertTrue(loginIntents.contains(LoginIntent.PasswordChanged("secret")))
        assertEquals(LoginIntent.Submit, loginIntents.last())
    }

    @Test
    fun loginLinksOpenRegisterAndReset() {
        showLogin()
        compose.onNodeWithText(text(R.string.login_to_register)).performClick()
        compose.onNodeWithText(text(R.string.login_forgot)).performClick()
        assertEquals(listOf("register", "forgot"), calls)
    }

    @Test
    fun loginInvalidCredentialsIsAnAssertiveMessageAndFieldErrorsShow() {
        showLogin(LoginState(error = LoginError.INVALID_CREDENTIALS, emailError = LoginFieldError.EMAIL_EMPTY))
        compose.onNodeWithText(text(R.string.login_error_invalid))
            .assertIsDisplayed().assert(liveRegion(LiveRegionMode.Assertive))
        compose.onNodeWithText(text(R.string.auth_field_email_empty)).assertIsDisplayed()
    }

    @Test
    fun loginWhileLoadingBlocksTheSubmitButton() {
        showLogin(LoginState(isLoading = true))
        compose.onNodeWithText(text(R.string.login_loading)).assertIsDisplayed()
        compose.onNodeWithTag(AUTH_SUBMIT_TAG).assertIsNotEnabled()
    }

    @Test
    fun registerShowsMaskedPasswordGenericConflictAndWeakPassword() {
        showRegister(
            RegisterState(error = RegisterError.ACCOUNT_UNAVAILABLE, passwordError = RegisterFieldError.PASSWORD_WEAK)
        )
        compose.onNodeWithTag(AUTH_PASSWORD_TAG)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithText(text(R.string.register_error_unavailable))
            .assertIsDisplayed().assert(liveRegion(LiveRegionMode.Assertive))
        compose.onNodeWithText(text(R.string.register_password_weak)).assertIsDisplayed()
    }

    @Test
    fun registerSendsIntentsAndGoesBack() {
        showRegister()
        compose.onNodeWithTag(AUTH_EMAIL_TAG).performTextInput("ana@example.test")
        compose.onNodeWithTag(AUTH_SUBMIT_TAG).performClick()
        compose.onNodeWithText(text(R.string.auth_back_to_login)).performClick()
        assertEquals(listOf(RegisterIntent.EmailChanged("ana@example.test"), RegisterIntent.Submit), registerIntents)
        assertEquals(listOf("back"), calls)
    }

    @Test
    fun registerWhileLoadingBlocksTheSubmitButton() {
        showRegister(RegisterState(isLoading = true))
        compose.onNodeWithText(text(R.string.register_loading)).assertIsDisplayed()
        compose.onNodeWithTag(AUTH_SUBMIT_TAG).assertIsNotEnabled()
    }

    @Test
    fun resetShowsTheUniformConfirmationPolitely() {
        showReset(ResetPasswordState(email = "ana@example.test", sent = true))
        compose.onNodeWithText(text(R.string.reset_sent))
            .assertIsDisplayed().assert(liveRegion(LiveRegionMode.Polite))
    }

    @Test
    fun resetShowsFieldErrorSendsIntentsAndGoesBack() {
        showReset(ResetPasswordState(emailError = ResetFieldError.EMAIL_INVALID))
        compose.onNodeWithText(text(R.string.auth_field_email_invalid)).assertIsDisplayed()
        compose.onNodeWithTag(AUTH_EMAIL_TAG).performTextInput("x")
        compose.onNodeWithTag(AUTH_SUBMIT_TAG).performClick()
        compose.onNodeWithText(text(R.string.auth_back_to_login)).performClick()
        assertEquals(listOf(ResetPasswordIntent.EmailChanged("x"), ResetPasswordIntent.Submit), resetIntents)
        assertEquals(listOf("back"), calls)
    }


    @Test
    fun loginFieldsDeclareAutofillTypes() {
        showLogin()
        assertEquals(
            listOf(listOf(AutofillType.Username, AutofillType.EmailAddress), listOf(AutofillType.Password)),
            autofillTypes()
        )
    }

    @Test
    fun registerFieldsDeclareNewPasswordAndEmailAutofillTypes() {
        showRegister()
        assertEquals(
            listOf(listOf(AutofillType.Username, AutofillType.EmailAddress), listOf(AutofillType.NewPassword)),
            autofillTypes()
        )
    }

    @Test
    fun resetEmailDeclaresEmailAutofillTypes() {
        showReset()
        assertEquals(listOf(listOf(AutofillType.EmailAddress, AutofillType.Username)), autofillTypes())
    }

    @Test
    fun autofilledValuesReachTheViewModelAsIntents() {
        showLogin()
        compose.runOnIdle { autofillTree.children.values.forEach { it.onFill?.invoke("filled") } }
        assertEquals(
            listOf(LoginIntent.EmailChanged("filled"), LoginIntent.PasswordChanged("filled")),
            loginIntents
        )
    }

    @Test
    fun fieldErrorsAreAnnouncedPolitely() {
        showLogin(LoginState(emailError = LoginFieldError.EMAIL_EMPTY))
        compose.onNodeWithText(text(R.string.auth_field_email_empty)).assert(liveRegion(LiveRegionMode.Polite))
    }

    @Test
    fun afterASuccessfulSubmitTheButtonStaysBlockedUntilTheSessionSwapsTheScreen() {
        showLogin(LoginState(succeeded = true))
        compose.onNodeWithTag(AUTH_SUBMIT_TAG).assertIsNotEnabled()
    }

    @Test
    fun registerStaysBlockedAfterASuccessfulSubmit() {
        showRegister(RegisterState(succeeded = true))
        compose.onNodeWithTag(AUTH_SUBMIT_TAG).assertIsNotEnabled()
    }

    @Test
    fun theImeNextActionMovesFocusFromEmailToPassword() {
        showLogin()
        compose.onNodeWithTag(AUTH_EMAIL_TAG).performClick().performImeAction()
        compose.onNodeWithTag(AUTH_PASSWORD_TAG).assertIsFocused()
    }
}
