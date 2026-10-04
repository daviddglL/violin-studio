package com.violinstudio.ui.feature.consent.view

import android.app.Application
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.account.usecase.DeleteAccountUseCase
import com.violinstudio.domain.feature.auth.usecase.GetOwnEmailUseCase
import com.violinstudio.domain.feature.auth.usecase.SignOutUseCase
import com.violinstudio.domain.feature.consent.failure.ConsentFailure
import com.violinstudio.domain.feature.consent.model.GuardianRequestReceipt
import com.violinstudio.domain.feature.consent.model.IdentityConfig
import com.violinstudio.domain.feature.consent.usecase.AcceptPolicyUseCase
import com.violinstudio.domain.feature.consent.usecase.GetIdentityConfigUseCase
import com.violinstudio.domain.feature.consent.usecase.RequestGuardianConsentUseCase
import com.violinstudio.domain.feature.profile.model.ConsentStatus
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.profile.model.Role
import com.violinstudio.domain.feature.profile.model.UserProfile
import com.violinstudio.domain.feature.session.ConsentReason
import com.violinstudio.domain.feature.session.SessionRefreshTrigger
import com.violinstudio.domain.feature.session.SessionState
import com.violinstudio.ui.R
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.account.view.DELETE_ACCOUNT_BUTTON_TAG
import com.violinstudio.ui.feature.account.view.DELETE_ACCOUNT_CONFIRM_TAG
import com.violinstudio.ui.feature.account.view.DELETE_ACCOUNT_MESSAGE_TAG
import com.violinstudio.ui.feature.account.viewmodel.DeleteAccountViewModel
import com.violinstudio.ui.feature.auth.view.AUTH_EMAIL_TAG
import com.violinstudio.ui.feature.auth.view.AUTH_SUBMIT_TAG
import com.violinstudio.ui.feature.consent.viewmodel.ConsentViewModel
import com.violinstudio.ui.feature.guardian.view.GUARDIAN_REQUEST_TAG
import com.violinstudio.ui.feature.guardian.viewmodel.GuardianRequestViewModel
import com.violinstudio.ui.feature.session.view.PlaceholderScreen
import com.violinstudio.ui.navigation.SessionNavHost
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * El destino `consent` dentro del host de sesión con el ViewModel real y los casos de uso simulados: re-consentimiento
 * en caliente (bump y revocación), reintento sin avanzar y apertura segura de la política.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ConsentFlowTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private val v1 = IdentityConfig(1, "https://example.test/policy/1", 14, true)
    private val v2 = IdentityConfig(2, "https://example.test/policy/2", 14, true)
    private val ready = SessionState.Ready(
        UserProfile(
            "u1", "Ana", Instrument.VIOLIN, "es", Role.INDEPENDENT, false, ConsentStatus.GRANTED, 1, null, false
        )
    )

    private val accept = mockk<AcceptPolicyUseCase>()
    private val getConfig = mockk<GetIdentityConfigUseCase>()
    private val delete = mockk<DeleteAccountUseCase>(relaxed = true)
    private val signOut = mockk<SignOutUseCase>(relaxed = true)
    private val trigger = mockk<SessionRefreshTrigger>(relaxed = true)
    private val requestGuardian = mockk<RequestGuardianConsentUseCase>()
    private val ownEmail = mockk<GetOwnEmailUseCase> { coEvery { this@mockk() } returns "me@example.com" }
    private val session = mutableStateOf<SessionState>(ready)
    private var startActivityFailure: RuntimeException? = null

    private val failingContext = object : ContextWrapper(context) {
        override fun startActivity(intent: Intent) {
            startActivityFailure?.let { throw it } ?: super.startActivity(intent)
        }
    }

    private fun start(initial: SessionState) {
        session.value = initial
        val viewModel = ConsentViewModel(accept, getConfig, signOut, trigger)
        val deleteViewModel = DeleteAccountViewModel(delete, mockk(), mockk())
        val guardianViewModel = GuardianRequestViewModel(requestGuardian, ownEmail, signOut, trigger)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides failingContext) {
                ViolinStudioTheme {
                    SessionNavHost(
                        session = session.value,
                        onSignOut = {},
                        home = { PlaceholderScreen("home") },
                        deleteViewModel = { deleteViewModel },
                        consent = { ConsentSlot(it, minorViewModel = { guardianViewModel }) { viewModel } }
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun tickAndAccept() {
        compose.onNodeWithTag(CONSENT_CHECKBOX_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(AUTH_SUBMIT_TAG).performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test
    fun `a hot policy bump shows the new version and after accepting the session returns to ready`() {
        coEvery { accept(any()) } returns Result.success(Unit)
        start(ready)
        compose.onNodeWithTag("home").assertIsDisplayed()
        session.value = SessionState.ConsentPending(v2, isMinor = false, reason = ConsentReason.POLICY_UPDATED)
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.consent_intro_updated, 2)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.consent_checkbox, 2)).performScrollTo().assertIsDisplayed()
        tickAndAccept()
        coVerify(exactly = 1) { accept(2) }
        session.value = ready
        compose.waitForIdle()
        compose.onNodeWithTag("home").assertIsDisplayed()
        compose.onNodeWithTag(CONSENT_CHECKBOX_TAG).assertDoesNotExist()
    }

    @Test
    fun `a bump while the screen is open swaps the policy and clears the checkbox`() {
        start(SessionState.ConsentPending(v1, isMinor = false, reason = ConsentReason.FIRST))
        compose.onNodeWithTag(CONSENT_CHECKBOX_TAG).performScrollTo().performClick()
        session.value = SessionState.ConsentPending(v2, isMinor = false, reason = ConsentReason.POLICY_UPDATED)
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.consent_checkbox, 2)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(AUTH_SUBMIT_TAG).performScrollTo()
        compose.onNodeWithTag(AUTH_SUBMIT_TAG).assertIsNotEnabled()
    }

    @Test
    fun `a revoked adult sees the re-consent copy and can delete the account`() {
        coEvery { delete() } returns Result.success(Unit)
        start(SessionState.ConsentPending(v2, isMinor = false, reason = ConsentReason.REVOKED))
        compose.onNodeWithText(text(R.string.consent_intro_revoked, 2)).assertIsDisplayed()
        compose.onNodeWithTag(DELETE_ACCOUNT_BUTTON_TAG).performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(DELETE_ACCOUNT_CONFIRM_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        // Abrir el flujo no borra: hace falta la confirmacion explicita.
        coVerify(exactly = 0) { delete() }
        compose.onNodeWithTag(DELETE_ACCOUNT_CONFIRM_TAG).performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(DELETE_ACCOUNT_MESSAGE_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        coVerify(exactly = 1) { delete() }
    }

    @Test
    fun `a minor sees the guardian request instead of the adult consent and sends the typed email`() {
        coEvery { requestGuardian(any()) } returns Result.success(GuardianRequestReceipt("t***@example.com"))
        start(SessionState.ConsentPending(v1, isMinor = true, reason = ConsentReason.FIRST))
        compose.onNodeWithTag(GUARDIAN_REQUEST_TAG).assertIsDisplayed()
        compose.onNodeWithTag(CONSENT_CHECKBOX_TAG).assertDoesNotExist()
        compose.onNodeWithTag(AUTH_EMAIL_TAG).performTextInput("tutor@example.com")
        compose.onNodeWithTag(AUTH_SUBMIT_TAG).performScrollTo().performClick()
        compose.waitForIdle()
        coVerify(exactly = 1) { requestGuardian("tutor@example.com") }
    }

    @Test
    fun `a revoked minor sees the revoked copy`() {
        start(SessionState.ConsentPending(v1, isMinor = true, reason = ConsentReason.REVOKED))
        compose.onNodeWithText(text(R.string.guardian_request_intro_revoked)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.guardian_request_intro_first)).assertDoesNotExist()
    }

    @Test
    fun `a failed claims refresh shows a retry message and the session does not advance`() {
        coEvery { accept(any()) } returns Result.failure(ConsentFailure.Network)
        start(SessionState.ConsentPending(v1, isMinor = false, reason = ConsentReason.FIRST))
        tickAndAccept()
        compose.onNodeWithText(text(R.string.consent_error_network)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(CONSENT_CHECKBOX_TAG).assertIsDisplayed()
        compose.onNodeWithTag("home").assertDoesNotExist()
        coEvery { accept(any()) } returns Result.success(Unit)
        compose.onNodeWithTag(AUTH_SUBMIT_TAG).performScrollTo().performClick()
        compose.waitForIdle()
        coVerify(exactly = 2) { accept(1) }
    }

    @Test
    fun `an outdated policy reloads the server config and asks to accept the new version`() {
        coEvery { accept(1) } returns Result.failure(ConsentFailure.PolicyOutdated(2))
        coEvery { getConfig() } returns Result.success(v2)
        start(SessionState.ConsentPending(v1, isMinor = false, reason = ConsentReason.FIRST))
        tickAndAccept()
        compose.onNodeWithText(text(R.string.consent_error_policy_changed, 2)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.consent_checkbox, 2)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(AUTH_SUBMIT_TAG).assertIsNotEnabled()
        coEvery { accept(2) } returns Result.success(Unit)
        tickAndAccept()
        coVerify(exactly = 1) { accept(2) }
    }

    @Test
    fun `the policy link opens the server https url in a viewer`() {
        start(SessionState.ConsentPending(v1, isMinor = false, reason = ConsentReason.FIRST))
        compose.onNodeWithTag(CONSENT_POLICY_LINK_TAG).performScrollTo().performClick()
        compose.waitForIdle()
        val started: Intent = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://example.test/policy/1", started.dataString)
    }

    @Test
    fun `a non https policy url is never opened and the user is told`() {
        val insecure = IdentityConfig(1, "http://example.test/policy", 14, true)
        start(SessionState.ConsentPending(insecure, isMinor = false, reason = ConsentReason.FIRST))
        compose.onNodeWithTag(CONSENT_POLICY_LINK_TAG).performScrollTo().performClick()
        compose.waitForIdle()
        assertNull(shadowOf(context).nextStartedActivity)
        compose.onNodeWithText(text(R.string.consent_link_failed)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `without an app to open the policy the user is told instead of crashing`() {
        shadowOf(context).checkActivities(true)
        start(SessionState.ConsentPending(v1, isMinor = false, reason = ConsentReason.FIRST))
        compose.onNodeWithTag(CONSENT_POLICY_LINK_TAG).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.consent_link_failed)).assertIsDisplayed()
    }

    @Test
    fun `a viewer that refuses the intent with a security exception tells the user instead of crashing`() {
        startActivityFailure = SecurityException("not exported")
        start(SessionState.ConsentPending(v1, isMinor = false, reason = ConsentReason.FIRST))
        compose.onNodeWithTag(CONSENT_POLICY_LINK_TAG).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.consent_link_failed)).assertIsDisplayed()
    }
}
