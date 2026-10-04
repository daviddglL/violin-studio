package com.violinstudio.ui.feature.guardian.view

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.session.ConsentReason
import com.violinstudio.ui.R
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.account.view.FAKE_DELETE_TAG
import com.violinstudio.ui.feature.account.view.LocalDeleteAccount
import com.violinstudio.ui.feature.account.view.fakeDeleteScope
import com.violinstudio.ui.feature.auth.view.AUTH_EMAIL_TAG
import com.violinstudio.ui.feature.auth.view.AUTH_SUBMIT_TAG
import com.violinstudio.ui.feature.guardian.viewmodel.GuardianEmailError
import com.violinstudio.ui.feature.guardian.viewmodel.GuardianRequestError
import com.violinstudio.ui.feature.guardian.viewmodel.GuardianRequestIntent
import com.violinstudio.ui.feature.guardian.viewmodel.GuardianRequestState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class GuardianRequestScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)
    private val intents = mutableListOf<GuardianRequestIntent>()

    private fun show(state: GuardianRequestState = GuardianRequestState(), deleteActive: Boolean = false) =
        compose.setContent {
            ViolinStudioTheme {
                CompositionLocalProvider(LocalDeleteAccount provides fakeDeleteScope(deleteActive)) {
                    GuardianRequestScreen(state) { intents += it }
                }
            }
        }

    @Test
    fun `first request explains the guardian is needed and offers email, send, delete and sign out`() {
        show()
        compose.onNodeWithTag(GUARDIAN_REQUEST_TAG).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.guardian_request_title_first)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.guardian_request_intro_first)).assertIsDisplayed()
        compose.onNodeWithTag(AUTH_EMAIL_TAG).assertIsDisplayed()
        compose.onNodeWithTag(AUTH_SUBMIT_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(FAKE_DELETE_TAG).performScrollTo().assertIsEnabled()
        compose.onNodeWithTag(GUARDIAN_REQUEST_SIGN_OUT_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `an updated policy and a revocation change the copy`() {
        show(GuardianRequestState(reason = ConsentReason.POLICY_UPDATED))
        compose.onNodeWithText(text(R.string.guardian_request_intro_updated)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.guardian_request_intro_first)).assertDoesNotExist()
    }

    @Test
    fun `a revoked consent says the guardian must confirm again`() {
        show(GuardianRequestState(reason = ConsentReason.REVOKED))
        compose.onNodeWithText(text(R.string.guardian_request_title_revoked)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.guardian_request_intro_revoked)).assertIsDisplayed()
    }

    @Test
    fun `typing sends the email and send is enabled once there is one`() {
        show(GuardianRequestState(email = "t@example.com"))
        compose.onNodeWithTag(AUTH_EMAIL_TAG).performTextInput("x")
        compose.onNodeWithTag(AUTH_SUBMIT_TAG).performScrollTo().assertIsEnabled().performClick()
        assertEquals(
            listOf(
                GuardianRequestIntent.EmailChanged("xt@example.com"),
                GuardianRequestIntent.SubmitGuardianEmail
            ),
            intents
        )
    }

    @Test
    fun `an invalid email shows a polite field error`() {
        show(GuardianRequestState(email = "mal", emailError = GuardianEmailError.INVALID))
        compose.onNode(hasText(text(R.string.guardian_request_email_invalid))).assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun `rate limited tells the wait in minutes, rounding up, and is assertive`() {
        show(
            GuardianRequestState(
                email = "t@example.com",
                error = GuardianRequestError.RATE_LIMITED,
                retryAfterSeconds = 61
            )
        )
        compose.onNode(hasText(text(R.string.guardian_request_error_rate_limited_minutes, 2))).performScrollTo()
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
    }

    @Test
    fun `rate limited without a known wait uses the generic message`() {
        show(GuardianRequestState(email = "t@example.com", error = GuardianRequestError.RATE_LIMITED))
        compose.onNodeWithText(text(R.string.guardian_request_error_rate_limited)).performScrollTo().assertIsDisplayed()
    }

    private fun assertErrorShown(error: GuardianRequestError, message: Int) {
        show(GuardianRequestState(email = "t@e.co", error = error))
        compose.onNodeWithText(text(message)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a network error has its own message`() =
        assertErrorShown(GuardianRequestError.NETWORK, R.string.guardian_request_error_network)

    @Test
    fun `not minor has its own message`() =
        assertErrorShown(GuardianRequestError.NOT_MINOR, R.string.guardian_request_error_not_minor)

    @Test
    fun `an unknown error has its own message`() =
        assertErrorShown(GuardianRequestError.UNKNOWN, R.string.guardian_request_error_unknown)

    @Test
    fun `after success the form is replaced by a polite sent message and delete and sign out remain`() {
        show(GuardianRequestState(email = "t@example.com", succeeded = true))
        compose.onNode(hasText(text(R.string.guardian_request_sent))).assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithTag(AUTH_SUBMIT_TAG).assertDoesNotExist()
        compose.onNodeWithTag(AUTH_EMAIL_TAG).assertDoesNotExist()
        compose.onNodeWithTag(GUARDIAN_REQUEST_SIGN_OUT_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `already approved says it is continuing instead of showing a dead form`() {
        show(GuardianRequestState(succeeded = true, alreadyApproved = true))
        compose.onNode(hasText(text(R.string.guardian_request_already_approved))).assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithText(text(R.string.guardian_request_sent)).assertDoesNotExist()
        compose.onNodeWithTag(AUTH_SUBMIT_TAG).assertDoesNotExist()
    }

    @Test
    fun `an unavailable error has its own message`() =
        assertErrorShown(GuardianRequestError.UNAVAILABLE, R.string.guardian_request_error_unavailable)

    @Test
    fun `the own email error says to use a different one`() {
        show(GuardianRequestState(email = "me@example.com", emailError = GuardianEmailError.OWN_EMAIL))
        compose.onNode(hasText(text(R.string.guardian_request_email_own))).assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun `while sending, send and delete are blocked`() {
        show(GuardianRequestState(email = "t@example.com", isLoading = true))
        compose.onNodeWithText(text(R.string.guardian_request_sending)).assertIsDisplayed()
        compose.onNodeWithTag(AUTH_SUBMIT_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(FAKE_DELETE_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `sign out sends its intent and the delete entry is the shared one`() {
        show()
        compose.onNodeWithTag(FAKE_DELETE_TAG).performScrollTo().assertIsEnabled()
        compose.onNodeWithTag(GUARDIAN_REQUEST_SIGN_OUT_TAG).performScrollTo().performClick()
        assertEquals(listOf<GuardianRequestIntent>(GuardianRequestIntent.SignOut), intents)
    }

    @Test
    fun `while the shared delete flow is active send and sign out are blocked`() {
        show(GuardianRequestState(email = "t@example.com"), deleteActive = true)
        compose.onNodeWithTag(AUTH_SUBMIT_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(GUARDIAN_REQUEST_SIGN_OUT_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `deleting stays available after success so the screen is never a dead end`() {
        show(GuardianRequestState(email = "t@example.com", succeeded = true))
        compose.onNodeWithTag(FAKE_DELETE_TAG).performScrollTo().assertIsEnabled()
    }

    @Test
    fun `the keyboard done action cannot send while the shared delete flow is active`() {
        show(GuardianRequestState(email = "t@example.com"), deleteActive = true)
        compose.onNodeWithTag(AUTH_EMAIL_TAG).assertIsNotEnabled()
        runCatching { compose.onNodeWithTag(AUTH_EMAIL_TAG).performImeAction() }
        assertEquals(emptyList<GuardianRequestIntent>(), intents)
    }

    @Test
    fun `the keyboard done action sends when nothing blocks it`() {
        show(GuardianRequestState(email = "t@example.com"))
        compose.onNodeWithTag(AUTH_EMAIL_TAG).performImeAction()
        assertEquals(listOf<GuardianRequestIntent>(GuardianRequestIntent.SubmitGuardianEmail), intents)
    }
}
