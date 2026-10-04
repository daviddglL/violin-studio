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
import com.violinstudio.domain.feature.account.usecase.DeleteAccountUseCase
import com.violinstudio.domain.feature.auth.usecase.GetOwnEmailUseCase
import com.violinstudio.domain.feature.auth.usecase.GetOwnUidUseCase
import com.violinstudio.domain.feature.auth.usecase.SignOutUseCase
import com.violinstudio.domain.feature.consent.PendingGuardianEmail
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
    private val delete = mockk<DeleteAccountUseCase>(relaxed = true)
    private val signOut = mockk<SignOutUseCase>(relaxed = true)
    private val trigger = mockk<SessionRefreshTrigger>(relaxed = true)
    private val session = mutableStateOf<SessionState>(SessionState.ParentalPending("t***@example.com", 1))

    private fun start() {
        val viewModel = GuardianWaitViewModel(request, pendingEmail, ownEmail, ownUid, delete, signOut, trigger)
        compose.setContent {
            ViolinStudioTheme {
                SessionNavHost(
                    session = session.value,
                    onSignOut = {},
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
}
