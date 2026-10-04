package com.violinstudio.ui.feature.account.view

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.account.failure.AccountFailure
import com.violinstudio.domain.feature.account.usecase.DeleteAccountUseCase
import com.violinstudio.domain.feature.auth.usecase.GetReauthMethodUseCase
import com.violinstudio.domain.feature.auth.usecase.ReauthMethod
import com.violinstudio.domain.feature.auth.usecase.ReauthenticateUseCase
import com.violinstudio.domain.feature.consent.model.IdentityConfig
import com.violinstudio.domain.feature.profile.model.ConsentStatus
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.profile.model.Role
import com.violinstudio.domain.feature.profile.model.UserProfile
import com.violinstudio.domain.feature.session.SessionState
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.account.viewmodel.DeleteAccountViewModel
import com.violinstudio.ui.feature.auth.view.VerifyEmailScreen
import com.violinstudio.ui.feature.auth.viewmodel.VerifyEmailState
import com.violinstudio.ui.feature.consent.view.ConsentScreen
import com.violinstudio.ui.feature.consent.viewmodel.ConsentState
import com.violinstudio.ui.feature.guardian.view.GuardianWaitScreen
import com.violinstudio.ui.feature.guardian.viewmodel.GuardianWaitState
import com.violinstudio.ui.feature.onboarding.view.OnboardingScreen
import com.violinstudio.ui.feature.onboarding.viewmodel.OnboardingState
import com.violinstudio.ui.feature.settings.view.SettingsScreen
import com.violinstudio.ui.feature.settings.viewmodel.SettingsFields
import com.violinstudio.ui.feature.settings.viewmodel.SettingsState
import com.violinstudio.ui.navigation.SessionNavHost
import com.violinstudio.ui.navigation.SettingsDestination
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * D1 de punta a punta: las cinco pantallas reales (con estados fijos) dentro del host de sesion, con el ViewModel real
 * del borrado y solo los casos de uso simulados. Todas ofrecen el mismo flujo y llegan al mismo `deleteAccount`.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class DeleteAccountHostTest {
    @get:Rule
    val compose = createComposeRule()

    private val delete = mockk<DeleteAccountUseCase>()
    private val reauth = mockk<ReauthenticateUseCase>()
    private val method = mockk<GetReauthMethodUseCase>()
    private val config = IdentityConfig(1, "https://example.test/policy/1", 14, true)
    private val profile =
        UserProfile(
            "u1", "Ana", Instrument.VIOLIN, "es", Role.INDEPENDENT, false, ConsentStatus.GRANTED, 1, null, false
        )
    private val fields = SettingsFields("Ana", Instrument.VIOLIN, "es")
    private lateinit var nav: NavHostController

    private fun start(session: SessionState, openSettings: Boolean = false) {
        val deleteViewModel = DeleteAccountViewModel(delete, reauth, method)
        compose.setContent {
            ViolinStudioTheme {
                nav = rememberNavController()
                SessionNavHost(
                    session = session,
                    onSignOut = {},
                    navController = nav,
                    home = { },
                    deleteViewModel = { deleteViewModel },
                    verifyEmail = { VerifyEmailScreen(it, VerifyEmailState(), {}) },
                    onboarding = { OnboardingScreen(OnboardingState(), {}) },
                    consent = { ConsentScreen(ConsentState(config = config), {}) },
                    guardianWait = { GuardianWaitScreen(GuardianWaitState(emailMasked = "t***@example.com"), {}) },
                    settings = { SettingsScreen(SettingsState(fields = fields, baseline = fields), {}, it) }
                )
            }
        }
        compose.waitForIdle()
        if (openSettings) {
            compose.runOnUiThread { nav.navigate(SettingsDestination) }
            compose.waitForIdle()
        }
    }

    private fun has(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun awaitConfirm() = compose.waitUntil(WAIT_MS) { has(DELETE_ACCOUNT_CONFIRM_TAG) }

    private companion object {
        const val WAIT_MS = 5_000L
    }

    private fun assertSameFlow() {
        coEvery { delete() } returns Result.success(Unit)
        compose.onNodeWithTag(DELETE_ACCOUNT_BUTTON_TAG).performScrollTo().performClick()
        awaitConfirm()
        compose.onNodeWithTag(DELETE_ACCOUNT_CONFIRM_TAG).performScrollTo().assertIsDisplayed()
        // Abrir no borra nada.
        coVerify(exactly = 0) { delete() }
        compose.onNodeWithTag(DELETE_ACCOUNT_CONFIRM_TAG).performClick()
        compose.waitUntil(WAIT_MS) { has(DELETE_ACCOUNT_MESSAGE_TAG) }
        coVerify(exactly = 1) { delete() }
        compose.onNodeWithTag(DELETE_ACCOUNT_MESSAGE_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `verify email offers the shared delete flow`() {
        start(SessionState.EmailUnverified("a@b.c"))
        assertSameFlow()
    }

    @Test
    fun `onboarding offers the shared delete flow`() {
        start(SessionState.NeedsProfile)
        assertSameFlow()
    }

    @Test
    fun `consent offers the shared delete flow`() {
        start(SessionState.ConsentPending(config, isMinor = false))
        assertSameFlow()
    }

    @Test
    fun `guardian wait offers the shared delete flow`() {
        start(SessionState.ParentalPending("t***@example.com", 1))
        assertSameFlow()
    }

    @Test
    fun `settings offers the shared delete flow`() {
        start(SessionState.Ready(profile), openSettings = true)
        assertSameFlow()
    }

    @Test
    fun `a recent login requirement is resolved inside the same screen with the password`() {
        coEvery { delete() } returnsMany
            listOf(Result.failure(AccountFailure.RequiresRecentLogin), Result.success(Unit))
        coEvery { method() } returns ReauthMethod.PASSWORD
        coEvery { reauth("hunter2") } returns Result.success(Unit)
        start(SessionState.NeedsProfile)
        compose.onNodeWithTag(DELETE_ACCOUNT_BUTTON_TAG).performScrollTo().performClick()
        awaitConfirm()
        compose.onNodeWithTag(DELETE_ACCOUNT_CONFIRM_TAG).performScrollTo().performClick()
        compose.waitUntil(WAIT_MS) { has(DELETE_ACCOUNT_PASSWORD_TAG) }
        compose.onNodeWithTag(DELETE_ACCOUNT_PASSWORD_TAG).performScrollTo().performTextInput("hunter2")
        compose.onNodeWithTag(DELETE_ACCOUNT_SUBMIT_TAG).performScrollTo().performClick()
        compose.waitUntil(WAIT_MS) { has(DELETE_ACCOUNT_MESSAGE_TAG) }
        coVerify(exactly = 2) { delete() }
        compose.onNodeWithTag(DELETE_ACCOUNT_MESSAGE_TAG).performScrollTo().assertIsDisplayed()
    }
}
