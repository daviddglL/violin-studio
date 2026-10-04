package com.violinstudio.ui.feature.guardian.view

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.auth.usecase.GetOwnEmailUseCase
import com.violinstudio.domain.feature.auth.usecase.GetOwnUidUseCase
import com.violinstudio.domain.feature.auth.usecase.SignOutUseCase
import com.violinstudio.domain.feature.consent.PendingGuardianEmail
import com.violinstudio.domain.feature.consent.failure.ConsentFailure
import com.violinstudio.domain.feature.consent.model.GuardianRequestReceipt
import com.violinstudio.domain.feature.consent.usecase.RequestGuardianConsentUseCase
import com.violinstudio.domain.feature.profile.model.ConsentStatus
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.profile.model.Role
import com.violinstudio.domain.feature.profile.model.UserProfile
import com.violinstudio.domain.feature.session.SessionRefreshTrigger
import com.violinstudio.domain.feature.session.SessionState
import com.violinstudio.ui.R
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.auth.view.AUTH_EMAIL_TAG
import com.violinstudio.ui.feature.auth.view.AUTH_SUBMIT_TAG
import com.violinstudio.ui.feature.guardian.viewmodel.GuardianWaitViewModel
import com.violinstudio.ui.feature.session.view.PlaceholderScreen
import com.violinstudio.ui.navigation.SessionNavHost
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** El destino `guardian_wait` dentro del host de sesión con el ViewModel real y los casos de uso simulados. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class GuardianWaitFlowTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private val request = mockk<RequestGuardianConsentUseCase>()
    private val pendingEmail = PendingGuardianEmail().also { it.remember("u1", "tutor@example.com") }
    private val ownEmail = mockk<GetOwnEmailUseCase> { coEvery { this@mockk() } returns "me@example.com" }
    private val ownUid = mockk<GetOwnUidUseCase> { coEvery { this@mockk() } returns "u1" }
    private val signOut = mockk<SignOutUseCase>(relaxed = true)
    private val trigger = mockk<SessionRefreshTrigger>(relaxed = true)
    private val session = mutableStateOf<SessionState>(SessionState.ParentalPending("t***@example.com", 1))

    private lateinit var viewModel: GuardianWaitViewModel

    private fun start() {
        viewModel = GuardianWaitViewModel(request, pendingEmail, ownEmail, ownUid, signOut, trigger)
        compose.setContent {
            ViolinStudioTheme {
                SessionNavHost(
                    session = session.value,
                    onSignOut = {},
                    auth = { PlaceholderScreen("auth") },
                    home = { PlaceholderScreen("home") },
                    guardianWait = { GuardianWaitSlot(it) { viewModel } }
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the first composition already shows the masked email from the session`() {
        start()
        compose.onNodeWithTag(GUARDIAN_WAIT_TAG).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.guardian_wait_intro_masked, "t***@example.com")).assertIsDisplayed()
    }

    @Test
    fun `resend sends to the remembered address and says so`() {
        coEvery { request(any()) } returns Result.success(GuardianRequestReceipt("t***@example.com"))
        start()
        compose.onNodeWithTag(GUARDIAN_WAIT_RESEND_TAG).performScrollTo().performClick()
        compose.waitForIdle()
        coVerify(exactly = 1) { request("tutor@example.com") }
        compose.onNodeWithText(text(R.string.guardian_wait_resent)).performScrollTo().assertIsDisplayed()
        // The remembered plain address never reaches the screen, only the masked one.
        compose.onNodeWithText("tutor@example.com", substring = true).assertDoesNotExist()
        compose.onNodeWithText("t***@example.com", substring = true).assertIsDisplayed()
    }

    @Test
    fun `changing the email sends the typed one and returns to the waiting view`() {
        coEvery { request(any()) } returns Result.success(GuardianRequestReceipt("n***@example.com"))
        start()
        compose.onNodeWithTag(GUARDIAN_WAIT_CHANGE_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(AUTH_EMAIL_TAG).performTextInput("otro@example.com")
        compose.onNodeWithTag(AUTH_SUBMIT_TAG).performScrollTo().performClick()
        compose.waitForIdle()
        coVerify(exactly = 1) { request("otro@example.com") }
        compose.onNodeWithText(text(R.string.guardian_wait_email_changed)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(AUTH_EMAIL_TAG).assertDoesNotExist()
    }

    @Test
    fun `once the guardian confirms the wait is replaced by home`() {
        start()
        session.value = SessionState.Ready(
            UserProfile(
                "u1", "Ana", Instrument.VIOLIN, "es", Role.INDEPENDENT, true, ConsentStatus.GRANTED, 1, null, false
            )
        )
        compose.waitForIdle()
        compose.onNodeWithTag("home").assertIsDisplayed()
        compose.onNodeWithTag(GUARDIAN_WAIT_TAG).assertDoesNotExist()
    }

    @Test
    fun `a newer parental pending replaces the masked email and the sends`() {
        start()
        session.value = SessionState.ParentalPending("n***@example.com", 2)
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.guardian_wait_intro_masked, "n***@example.com")).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.guardian_wait_intro_masked, "t***@example.com")).assertDoesNotExist()
        assertEquals(2, viewModel.state.value.sends)
        assertEquals("n***@example.com", viewModel.state.value.emailMasked)
    }

    @Test
    fun `a changed email shows the masked one the server answered even before the session catches up`() {
        coEvery { request(any()) } returns Result.success(GuardianRequestReceipt("n***@example.com"))
        start()
        compose.onNodeWithTag(GUARDIAN_WAIT_CHANGE_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(AUTH_EMAIL_TAG).performTextInput("otro@example.com")
        compose.onNodeWithTag(AUTH_SUBMIT_TAG).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.guardian_wait_intro_masked, "n***@example.com")).assertIsDisplayed()
        compose.onNodeWithText("otro@example.com", substring = true).assertDoesNotExist()
        session.value = SessionState.ParentalPending("m***@example.com", 3)
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.guardian_wait_intro_masked, "m***@example.com")).assertIsDisplayed()
    }

    @Test
    fun `already approved offers check again and asks the session to resolve again`() {
        coEvery { request(any()) } returns Result.failure(ConsentFailure.AlreadyGranted)
        start()
        compose.onNodeWithTag(GUARDIAN_WAIT_RESEND_TAG).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.guardian_request_already_approved)).performScrollTo().assertIsDisplayed()
        verify(exactly = 1) { trigger.requestRefresh() }
        compose.onNodeWithTag(GUARDIAN_WAIT_CHECK_TAG).performScrollTo().performClick()
        compose.waitForIdle()
        verify(exactly = 2) { trigger.requestRefresh() }
    }

    @Test
    fun `leaving the wait for another state leaves no stale guardian frame`() {
        start()
        session.value = SessionState.LoggedOut
        compose.waitForIdle()
        compose.onNodeWithTag(GUARDIAN_WAIT_TAG).assertDoesNotExist()
        compose.onNodeWithText("t***@example.com", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("auth").assertIsDisplayed()
    }
}
