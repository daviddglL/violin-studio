package com.violinstudio.ui.feature.guardian.view

import android.app.Application
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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.session.ConsentReason
import com.violinstudio.ui.R
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.auth.view.AUTH_EMAIL_TAG
import com.violinstudio.ui.feature.auth.view.AUTH_SUBMIT_TAG
import com.violinstudio.ui.feature.consent.viewmodel.ConsentDeleteError
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

    private fun show(state: GuardianRequestState = GuardianRequestState()) = compose.setContent {
        ViolinStudioTheme { GuardianRequestScreen(state) { intents += it } }
    }

    @Test
    fun `first request explains the guardian is needed and offers email, send, delete and sign out`() {
        show()
        compose.onNodeWithTag(GUARDIAN_REQUEST_TAG).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.guardian_request_title_first)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.guardian_request_intro_first)).assertIsDisplayed()
        compose.onNodeWithTag(AUTH_EMAIL_TAG).assertIsDisplayed()
        compose.onNodeWithTag(AUTH_SUBMIT_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(GUARDIAN_REQUEST_DELETE_TAG).performScrollTo().assertIsEnabled()
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
    fun `while sending, send and delete are blocked`() {
        show(GuardianRequestState(email = "t@example.com", isLoading = true))
        compose.onNodeWithText(text(R.string.guardian_request_sending)).assertIsDisplayed()
        compose.onNodeWithTag(AUTH_SUBMIT_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(GUARDIAN_REQUEST_DELETE_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `delete and sign out send their intents and a delete failure is shown`() {
        show(GuardianRequestState(deleteError = ConsentDeleteError.REAUTH_REQUIRED))
        compose.onNodeWithText(text(R.string.consent_delete_reauth)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(GUARDIAN_REQUEST_DELETE_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(GUARDIAN_REQUEST_SIGN_OUT_TAG).performScrollTo().performClick()
        assertEquals(listOf(GuardianRequestIntent.DeleteAccount, GuardianRequestIntent.SignOut), intents)
    }
}
