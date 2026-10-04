package com.violinstudio.ui.feature.account.view

import android.app.Application
import android.content.Context
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
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.auth.model.GoogleIdToken
import com.violinstudio.domain.feature.auth.usecase.ReauthMethod
import com.violinstudio.ui.R
import com.violinstudio.ui.commons.auth.GoogleIdTokenRequester
import com.violinstudio.ui.commons.auth.GoogleIdTokenResult
import com.violinstudio.ui.commons.auth.LocalGoogleIdTokenRequester
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.account.viewmodel.DeleteAccountError
import com.violinstudio.ui.feature.account.viewmodel.DeleteAccountIntent
import com.violinstudio.ui.feature.account.viewmodel.DeleteAccountState
import com.violinstudio.ui.feature.account.viewmodel.DeleteStep
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class DeleteAccountEntryTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private fun text(id: Int) = context.getString(id)
    private val intents = mutableListOf<DeleteAccountIntent>()
    private var current by mutableStateOf(DeleteAccountState())

    private var signOuts = 0

    private fun show(
        state: DeleteAccountState,
        enabled: Boolean = true,
        withSignOut: Boolean = true,
        google: GoogleIdTokenResult = GoogleIdTokenResult.Cancelled
    ) {
        current = state
        compose.setContent {
            ViolinStudioTheme {
                CompositionLocalProvider(LocalGoogleIdTokenRequester provides FixedRequester(google)) {
                    DeleteAccountEntry(current, { intents += it }, enabled, if (withSignOut) ({ signOuts++ }) else null)
                }
            }
        }
    }

    private class FixedRequester(private val result: GoogleIdTokenResult) : GoogleIdTokenRequester {
        override suspend fun request(context: Context) = result
    }

    private val confirming = DeleteAccountState(step = DeleteStep.CONFIRMING)
    private val reauthPassword = DeleteAccountState(step = DeleteStep.REAUTH, method = ReauthMethod.PASSWORD)
    private val reauthGoogle = DeleteAccountState(step = DeleteStep.REAUTH, method = ReauthMethod.GOOGLE)

    private fun assertive() = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive)
    private fun polite() = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    @Test
    fun `idle shows only the delete button and tapping it opens the flow`() {
        show(DeleteAccountState())
        compose.onNodeWithTag(DELETE_ACCOUNT_PANEL_TAG).assertDoesNotExist()
        compose.onNodeWithTag(DELETE_ACCOUNT_BUTTON_TAG).assertIsDisplayed().performClick()
        assertEquals(listOf(DeleteAccountIntent.Open), intents)
    }

    @Test
    fun `the entry button is disabled when the host screen is busy`() {
        show(DeleteAccountState(), enabled = false)
        compose.onNodeWithTag(DELETE_ACCOUNT_BUTTON_TAG).assertIsNotEnabled().performClick()
        assertEquals(emptyList<DeleteAccountIntent>(), intents)
    }

    @Test
    fun `confirmation is explicit, focuses the confirm button and nothing is deleted by opening it`() {
        show(DeleteAccountState())
        // Como en una app real: la ventana ya tiene el foco cuando se abre el panel.
        compose.runOnUiThread { compose.activity.window.decorView.requestFocus() }
        current = confirming
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.delete_account_confirm_message)).assertIsDisplayed()
        compose.onNodeWithTag(DELETE_ACCOUNT_CONFIRM_TAG).assertIsFocused().performClick()
        compose.onNodeWithTag(DELETE_ACCOUNT_CANCEL_TAG).performClick()
        assertEquals(listOf(DeleteAccountIntent.Confirm, DeleteAccountIntent.Cancel), intents)
    }

    @Test
    fun `while deleting both actions are disabled and the label shows progress`() {
        show(confirming.copy(isWorking = true))
        compose.onNodeWithTag(DELETE_ACCOUNT_CONFIRM_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(DELETE_ACCOUNT_CANCEL_TAG).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.delete_account_deleting)).assertIsDisplayed()
    }

    @Test
    fun `reauthentication with a password asks for it and submits only when filled`() {
        show(reauthPassword)
        compose.onNodeWithText(text(R.string.delete_account_reauth_message)).assertIsDisplayed()
        compose.onNodeWithTag(DELETE_ACCOUNT_SUBMIT_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(DELETE_ACCOUNT_PASSWORD_TAG).performTextInput("hunter2")
        assertEquals(listOf<DeleteAccountIntent>(DeleteAccountIntent.PasswordChanged("hunter2")), intents)
    }

    @Test
    fun `the password submit sends the intent when the state allows it`() {
        show(reauthPassword.copy(password = "hunter2"))
        compose.onNodeWithTag(DELETE_ACCOUNT_SUBMIT_TAG).performClick()
        assertEquals(listOf(DeleteAccountIntent.SubmitPassword), intents)
    }

    @Test
    fun `the password field and the buttons lock while reauthenticating`() {
        show(reauthPassword.copy(password = "x", isWorking = true))
        compose.onNodeWithTag(DELETE_ACCOUNT_PASSWORD_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(DELETE_ACCOUNT_SUBMIT_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(DELETE_ACCOUNT_CANCEL_TAG).assertIsNotEnabled()
    }

    @Test
    fun `a google account shows the google button and no password field`() {
        show(reauthGoogle)
        compose.onNodeWithTag(DELETE_ACCOUNT_GOOGLE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(DELETE_ACCOUNT_PASSWORD_TAG).assertDoesNotExist()
    }

    @Test
    fun `errors are assertive and say the account is still active`() {
        val cases = mapOf(
            DeleteAccountError.WRONG_PASSWORD to R.string.delete_account_error_wrong_password,
            DeleteAccountError.TOO_MANY_ATTEMPTS to R.string.delete_account_error_too_many,
            DeleteAccountError.NETWORK to R.string.delete_account_error_network,
            DeleteAccountError.PROVIDER_UNAVAILABLE to R.string.delete_account_error_provider,
            DeleteAccountError.REAUTH_UNAVAILABLE to R.string.delete_account_error_reauth_unavailable,
            DeleteAccountError.FAILED to R.string.delete_account_error_failed
        )
        show(confirming)
        for ((error, res) in cases) {
            current = confirming.copy(error = error)
            compose.waitForIdle()
            compose.onNodeWithTag(DELETE_ACCOUNT_MESSAGE_TAG).assertIsDisplayed().assert(assertive())
            compose.onNodeWithText(text(res)).assertIsDisplayed()
        }
    }

    @Test
    fun `a terminal reauth error removes the confirm action but keeps cancel`() {
        show(confirming.copy(error = DeleteAccountError.REAUTH_UNAVAILABLE))
        compose.onNodeWithTag(DELETE_ACCOUNT_CONFIRM_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(DELETE_ACCOUNT_CANCEL_TAG).performClick()
        assertEquals(listOf(DeleteAccountIntent.Cancel), intents)
    }

    @Test
    fun `after success there is no button and a polite notice instead of a dead end`() {
        show(DeleteAccountState(deleted = true))
        compose.onNodeWithTag(DELETE_ACCOUNT_BUTTON_TAG).assertDoesNotExist()
        compose.onNodeWithTag(DELETE_ACCOUNT_CONFIRM_TAG).assertDoesNotExist()
        compose.onNodeWithTag(DELETE_ACCOUNT_MESSAGE_TAG).assert(polite())
        compose.onNodeWithText(text(R.string.delete_account_done)).assertIsDisplayed()
    }

    @Test
    fun `after success there is a sign out escape in case the session does not change`() {
        show(DeleteAccountState(deleted = true))
        compose.onNodeWithTag(DELETE_ACCOUNT_SIGN_OUT_TAG).assertIsDisplayed().performClick()
        assertEquals(1, signOuts)
    }

    @Test
    fun `the sign out escape does not exist before success`() {
        show(confirming)
        compose.onNodeWithTag(DELETE_ACCOUNT_SIGN_OUT_TAG).assertDoesNotExist()
    }

    @Test
    fun `the sign out escape is not offered when the host gives none`() {
        show(DeleteAccountState(deleted = true), withSignOut = false)
        compose.onNodeWithTag(DELETE_ACCOUNT_SIGN_OUT_TAG).assertDoesNotExist()
    }

    @Test
    fun `cancelling the google sheet is silent and is not a provider failure`() {
        show(reauthGoogle, google = GoogleIdTokenResult.Cancelled)
        compose.onNodeWithTag(DELETE_ACCOUNT_GOOGLE_TAG).performClick()
        compose.waitForIdle()
        assertEquals(emptyList<DeleteAccountIntent>(), intents)
    }

    @Test
    fun `an unavailable google provider is reported and a token is forwarded`() {
        show(reauthGoogle, google = GoogleIdTokenResult.ProviderUnavailable)
        compose.onNodeWithTag(DELETE_ACCOUNT_GOOGLE_TAG).performClick()
        compose.waitForIdle()
        assertEquals(listOf<DeleteAccountIntent>(DeleteAccountIntent.GoogleFailed), intents)
    }

    @Test
    fun `a google token is forwarded to reauthenticate`() {
        val token = GoogleIdToken("t")
        show(reauthGoogle, google = GoogleIdTokenResult.Token(token))
        compose.onNodeWithTag(DELETE_ACCOUNT_GOOGLE_TAG).performClick()
        compose.waitForIdle()
        assertEquals(listOf<DeleteAccountIntent>(DeleteAccountIntent.GoogleToken(token)), intents)
    }
}
