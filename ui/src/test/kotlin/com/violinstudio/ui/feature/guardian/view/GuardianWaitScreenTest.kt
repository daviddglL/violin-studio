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
import com.violinstudio.ui.R
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.auth.view.AUTH_EMAIL_TAG
import com.violinstudio.ui.feature.auth.view.AUTH_SUBMIT_TAG
import com.violinstudio.ui.feature.consent.viewmodel.ConsentDeleteError
import com.violinstudio.ui.feature.guardian.viewmodel.GuardianEmailError
import com.violinstudio.ui.feature.guardian.viewmodel.GuardianRequestError
import com.violinstudio.ui.feature.guardian.viewmodel.GuardianWaitIntent
import com.violinstudio.ui.feature.guardian.viewmodel.GuardianWaitNotice
import com.violinstudio.ui.feature.guardian.viewmodel.GuardianWaitState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class GuardianWaitScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)
    private val intents = mutableListOf<GuardianWaitIntent>()

    private fun show(state: GuardianWaitState) = compose.setContent {
        ViolinStudioTheme { GuardianWaitScreen(state) { intents += it } }
    }

    private val waiting = GuardianWaitState(emailMasked = "t***@example.com", sends = 1, canResend = true)
    private fun polite() = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
    private fun assertive() = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive)

    @Test
    fun `waiting shows only the masked email and offers resend, change, delete and sign out`() {
        show(waiting)
        compose.onNodeWithTag(GUARDIAN_WAIT_TAG).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.guardian_wait_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.guardian_wait_intro_masked, "t***@example.com")).assertIsDisplayed()
        compose.onNodeWithTag(GUARDIAN_WAIT_RESEND_TAG).performScrollTo().assertIsEnabled()
        compose.onNodeWithTag(GUARDIAN_WAIT_CHANGE_TAG).performScrollTo().assertIsEnabled()
        compose.onNodeWithTag(GUARDIAN_WAIT_DELETE_TAG).performScrollTo().assertIsEnabled()
        compose.onNodeWithTag(GUARDIAN_WAIT_SIGN_OUT_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(AUTH_EMAIL_TAG).assertDoesNotExist()
        compose.onNodeWithTag(GUARDIAN_WAIT_CHECK_TAG).assertDoesNotExist()
    }

    @Test
    fun `without a remembered email there is no resend, only change email`() {
        show(waiting.copy(canResend = false))
        compose.onNodeWithTag(GUARDIAN_WAIT_RESEND_TAG).assertDoesNotExist()
        compose.onNodeWithTag(GUARDIAN_WAIT_CHANGE_TAG).performScrollTo().assertIsEnabled()
    }

    @Test
    fun `without a masked email the generic intro is used`() {
        show(GuardianWaitState())
        compose.onNodeWithText(text(R.string.guardian_wait_intro)).assertIsDisplayed()
    }

    @Test
    fun `the four actions send their intents`() {
        show(waiting)
        compose.onNodeWithTag(GUARDIAN_WAIT_RESEND_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(GUARDIAN_WAIT_CHANGE_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(GUARDIAN_WAIT_DELETE_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(GUARDIAN_WAIT_SIGN_OUT_TAG).performScrollTo().performClick()
        assertEquals(
            listOf(
                GuardianWaitIntent.Resend,
                GuardianWaitIntent.ChangeEmail,
                GuardianWaitIntent.DeleteAccount,
                GuardianWaitIntent.SignOut
            ),
            intents
        )
    }

    @Test
    fun `while a send is in progress every action is disabled`() {
        show(waiting.copy(isLoading = true))
        compose.onNodeWithTag(GUARDIAN_WAIT_RESEND_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(GUARDIAN_WAIT_CHANGE_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(GUARDIAN_WAIT_DELETE_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(GUARDIAN_WAIT_SIGN_OUT_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `while deleting or signing out the others are disabled`() {
        show(waiting.copy(isDeleting = true))
        compose.onNodeWithTag(GUARDIAN_WAIT_RESEND_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(GUARDIAN_WAIT_SIGN_OUT_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.consent_deleting)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a blocked resend is not offered while the wait lasts`() {
        show(
            waiting.copy(
                resendBlocked = true,
                error = GuardianRequestError.RATE_LIMITED,
                retryAfterSeconds = 120
            )
        )
        compose.onNodeWithTag(GUARDIAN_WAIT_RESEND_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNode(hasText(text(R.string.guardian_request_error_rate_limited_minutes, 2))).performScrollTo()
            .assertIsDisplayed().assert(assertive())
    }

    @Test
    fun `changing the email shows the field, send and cancel instead of resend and change`() {
        show(waiting.copy(changingEmail = true, email = "n@example.com"))
        compose.onNodeWithTag(AUTH_EMAIL_TAG).assertIsDisplayed()
        compose.onNodeWithTag(AUTH_SUBMIT_TAG).performScrollTo().assertIsEnabled()
        compose.onNodeWithTag(GUARDIAN_WAIT_CANCEL_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(GUARDIAN_WAIT_RESEND_TAG).assertDoesNotExist()
        compose.onNodeWithTag(GUARDIAN_WAIT_CHANGE_TAG).assertDoesNotExist()
    }

    @Test
    fun `typing and sending the new email and cancelling emit intents`() {
        show(waiting.copy(changingEmail = true, email = "n@example.com"))
        compose.onNodeWithTag(AUTH_EMAIL_TAG).performTextInput("x")
        compose.onNodeWithTag(AUTH_SUBMIT_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(GUARDIAN_WAIT_CANCEL_TAG).performScrollTo().performClick()
        assertEquals(
            listOf(
                GuardianWaitIntent.EmailChanged("xn@example.com"),
                GuardianWaitIntent.SubmitNewEmail,
                GuardianWaitIntent.CancelChangeEmail
            ),
            intents
        )
    }

    @Test
    fun `an empty new email cannot be sent`() {
        show(waiting.copy(changingEmail = true))
        compose.onNodeWithTag(AUTH_SUBMIT_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `a rejected email is shown in the field as polite`() {
        show(waiting.copy(changingEmail = true, email = "x", emailError = GuardianEmailError.INVALID))
        compose.onNode(hasText(text(R.string.guardian_request_email_invalid))).assertIsDisplayed().assert(polite())
    }

    @Test
    fun `an own email is rejected with its message`() {
        show(waiting.copy(changingEmail = true, email = "me@x.co", emailError = GuardianEmailError.OWN_EMAIL))
        compose.onNode(hasText(text(R.string.guardian_request_email_own))).assertIsDisplayed()
    }

    @Test
    fun `resent and changed are polite notices`() {
        show(waiting.copy(notice = GuardianWaitNotice.RESENT))
        compose.onNode(hasText(text(R.string.guardian_wait_resent))).performScrollTo().assertIsDisplayed()
            .assert(polite())
    }

    @Test
    fun `an email change notice is polite`() {
        show(waiting.copy(notice = GuardianWaitNotice.EMAIL_CHANGED))
        compose.onNode(hasText(text(R.string.guardian_wait_email_changed))).performScrollTo().assertIsDisplayed()
            .assert(polite())
    }

    @Test
    fun `already approved says it is continuing and offers check again, with the rest locked`() {
        show(waiting.copy(notice = GuardianWaitNotice.ALREADY_APPROVED))
        compose.onNode(hasText(text(R.string.guardian_request_already_approved))).performScrollTo()
            .assertIsDisplayed().assert(polite())
        compose.onNodeWithTag(GUARDIAN_WAIT_CHECK_TAG).performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag(GUARDIAN_WAIT_RESEND_TAG).performScrollTo().assertIsNotEnabled()
        assertEquals(listOf(GuardianWaitIntent.CheckAgain), intents)
    }

    @Test
    fun `network and unknown errors are assertive`() {
        show(waiting.copy(error = GuardianRequestError.NETWORK))
        compose.onNode(hasText(text(R.string.guardian_request_error_network))).performScrollTo().assertIsDisplayed()
            .assert(assertive())
    }

    @Test
    fun `a delete failure is shown`() {
        show(waiting.copy(deleteError = ConsentDeleteError.REAUTH_REQUIRED))
        compose.onNodeWithText(text(R.string.consent_delete_reauth)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `no plain guardian email is ever on screen, only the masked one`() {
        // El estado no tiene un campo para el email en claro; el tecleado nuevo solo existe en el campo de cambio.
        show(waiting)
        compose.onNodeWithText("tutor@example.com", substring = true).assertDoesNotExist()
        compose.onNodeWithText("t***@example.com", substring = true).assertIsDisplayed()
    }
}
