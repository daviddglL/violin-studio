package com.violinstudio.ui.feature.session.view

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.consent.model.IdentityConfig
import com.violinstudio.domain.feature.profile.model.ConsentStatus
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.profile.model.Role
import com.violinstudio.domain.feature.profile.model.UserProfile
import com.violinstudio.domain.feature.session.SessionState
import com.violinstudio.ui.R
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.navigation.GuardianWaitDestination
import com.violinstudio.ui.navigation.HomeDestination
import com.violinstudio.ui.navigation.SessionNavHost
import com.violinstudio.ui.navigation.SettingsDestination
import com.violinstudio.ui.navigation.TunerDestination
import com.violinstudio.ui.navigation.rootRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class AppNavHostSessionTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val config = IdentityConfig(1, "https://example.test/policy", 14, true)
    private val ready = SessionState.Ready(
        UserProfile(
            "u1", "Ana", Instrument.VIOLIN, "es", Role.INDEPENDENT, false, ConsentStatus.GRANTED, 1, null, false
        )
    )
    private val consentPending = SessionState.ConsentPending(config, isMinor = false)
    private val states = listOf(
        SessionState.Loading,
        SessionState.Unavailable,
        SessionState.LoggedOut,
        SessionState.EmailUnverified("a@b.c"),
        SessionState.NeedsProfile,
        consentPending,
        SessionState.ParentalPending("a***@b.c", 1),
        ready
    )

    private val session = mutableStateOf<SessionState>(SessionState.Loading)
    private var homeComposed = false
    private var settingsComposed = false
    private var tunerComposed = false
    private val guardianWaitSeen = mutableListOf<SessionState.ParentalPending>()
    private var signedOut = 0
    private lateinit var nav: NavHostController

    private fun start(initial: SessionState) {
        session.value = initial
        homeComposed = false
        settingsComposed = false
        tunerComposed = false
        compose.setContent {
            ViolinStudioTheme {
                nav = rememberNavController()
                SessionNavHost(
                    session = session.value,
                    onSignOut = { signedOut++ },
                    navController = nav,
                    auth = { PlaceholderScreen("auth") },
                    verifyEmail = { PlaceholderScreen("verify_email") },
                    onboarding = { PlaceholderScreen("onboarding") },
                    consent = { PlaceholderScreen("consent") },
                    guardianWait = {
                        guardianWaitSeen += it
                        PlaceholderScreen("guardian_wait")
                    },
                    home = { navigation ->
                        homeComposed = true
                        PlaceholderScreen("home")
                        Column {
                            Button(onClick = navigation.onOpenSettings, modifier = Modifier.testTag("open_settings")) {
                                Text("s")
                            }
                            Button(onClick = navigation.onOpenTuner, modifier = Modifier.testTag("open_tuner")) {
                                Text("t")
                            }
                        }
                    },
                    tuner = { onBack ->
                        tunerComposed = true
                        PlaceholderScreen("tuner")
                        Button(onClick = onBack, modifier = Modifier.testTag("tuner_back")) { Text("b") }
                    },
                    settings = { onBack ->
                        settingsComposed = true
                        PlaceholderScreen("settings")
                        Button(onClick = onBack, modifier = Modifier.testTag("settings_back")) { Text("b") }
                    }
                )
            }
        }
    }

    private fun assertAt(state: SessionState) {
        compose.waitForIdle()
        val route = state.rootRoute()
        val here = nav.currentDestination
        assertTrue("$state should be at $route but is $here", here?.hasRoute(route::class) == true)
        assertNull("back stack of $state must hold only its root", nav.previousBackStackEntry)
    }

    @Test
    fun everyStateNavigatesToItsRootAndClearsTheStack() {
        start(SessionState.Loading)
        states.forEach { state ->
            session.value = state
            assertAt(state)
        }
    }

    @Test
    fun nonReadyStatesNeverComposeBusinessContent() {
        start(SessionState.LoggedOut)
        states.filter { it !is SessionState.Ready }.forEach { state ->
            session.value = state
            assertAt(state)
        }
        assertFalse("home was composed without Ready", homeComposed)
    }

    @Test
    fun aRestoredBusinessRouteWithoutReadyRedirects() {
        start(SessionState.LoggedOut)
        assertAt(SessionState.LoggedOut)
        compose.runOnUiThread { nav.navigate(HomeDestination) }
        assertAt(SessionState.LoggedOut)
        assertFalse(homeComposed)
    }

    @Test
    fun aMinorWaitingForTheGuardianIsSentBackToTheWaitFromBusinessRoutes() {
        val waiting = SessionState.ParentalPending("t***@example.com", 1)
        start(waiting)
        assertAt(waiting)
        compose.onNodeWithTag("guardian_wait").assertIsDisplayed()
        compose.runOnUiThread { nav.navigate(HomeDestination) }
        assertAt(waiting)
        compose.onNodeWithTag("guardian_wait").assertIsDisplayed()
        assertFalse("home was composed while the guardian is pending", homeComposed)
    }

    @Test
    fun theWaitSlotReceivesTheParentalPendingAndKeepsTheLastOneWhileLeaving() {
        val waiting = SessionState.ParentalPending("t***@example.com", 1)
        start(waiting)
        assertAt(waiting)
        assertEquals(waiting, guardianWaitSeen.last())
        val resent = SessionState.ParentalPending("t***@example.com", 2)
        session.value = resent
        assertAt(resent)
        assertEquals(resent, guardianWaitSeen.last())
        // Leaving: the exit transition must still compose the slot with the last pending, never without data.
        session.value = ready
        assertAt(ready)
        assertTrue(guardianWaitSeen.all { it.emailMasked == "t***@example.com" })
    }

    @Test
    fun settingsIsReachableFromHomeAndBackReturnsToIt() {
        start(ready)
        assertAt(ready)
        compose.onNodeWithTag("open_settings").performClick()
        compose.waitForIdle()
        assertTrue(nav.currentDestination?.hasRoute(SettingsDestination::class) == true)
        compose.onNodeWithTag("settings").assertIsDisplayed()
        compose.onNodeWithTag("settings_back").performClick()
        assertAt(ready)
    }

    @Test
    fun tunerIsReachableFromHomeAndBackReturnsToIt() {
        start(ready)
        assertAt(ready)
        compose.onNodeWithTag("open_tuner").performClick()
        compose.waitForIdle()
        assertTrue(nav.currentDestination?.hasRoute(TunerDestination::class) == true)
        compose.onNodeWithTag("tuner").assertIsDisplayed()
        compose.onNodeWithTag("tuner_back").performClick()
        assertAt(ready)
    }

    @Test
    fun tunerWithoutReadyRedirectsAndIsNeverComposed() {
        start(SessionState.LoggedOut)
        compose.runOnUiThread { nav.navigate(TunerDestination) }
        assertAt(SessionState.LoggedOut)
        assertFalse(tunerComposed)
    }

    @Test
    fun leavingReadyFromTheTunerRedirectsToTheNewRoot() {
        start(ready)
        compose.runOnUiThread { nav.navigate(TunerDestination) }
        compose.waitForIdle()
        session.value = consentPending
        assertAt(consentPending)
    }

    @Test
    fun settingsWithoutReadyRedirectsAndIsNeverComposed() {
        start(SessionState.LoggedOut)
        compose.runOnUiThread { nav.navigate(SettingsDestination) }
        assertAt(SessionState.LoggedOut)
        assertFalse(settingsComposed)
    }

    @Test
    fun revokingFromSettingsRedirectsToConsentAndBusinessRoutesStayClosed() {
        start(ready)
        compose.runOnUiThread { nav.navigate(SettingsDestination) }
        compose.waitForIdle()
        assertTrue(nav.currentDestination?.hasRoute(SettingsDestination::class) == true)
        session.value = consentPending
        assertAt(consentPending)
        compose.runOnUiThread { nav.navigate(SettingsDestination) }
        assertAt(consentPending)
    }

    @Test
    fun hotChangesFromReadyRedirect() {
        start(ready)
        assertAt(ready)
        compose.onNodeWithTag("home").assertIsDisplayed()
        session.value = consentPending
        assertAt(consentPending)
        session.value = ready
        assertAt(ready)
        session.value = SessionState.NeedsProfile
        assertAt(SessionState.NeedsProfile)
    }

    @Test
    fun signingOutFromAnyStateLeavesOnlyAuthOnTheStack() {
        start(SessionState.Loading)
        states.filter { it !is SessionState.LoggedOut && it !is SessionState.Loading }.forEach { state ->
            session.value = state
            assertAt(state)
            session.value = SessionState.LoggedOut
            assertAt(SessionState.LoggedOut)
        }
    }

    @Test
    fun offlineScreenOffersOnlySignOut() {
        start(SessionState.Unavailable)
        compose.waitForIdle()
        compose.onNodeWithTag(OFFLINE_TAG).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.session_sign_out)).performClick()
        assertEquals(1, signedOut)
    }

    @Test
    fun loadingShowsTheSplash() {
        start(SessionState.Loading)
        compose.onNodeWithTag(SPLASH_TAG).assertIsDisplayed()
    }

    @Test
    fun aTransientOfflineBlipKeepsTheHomeEntryAndOverlaysOffline() {
        start(ready)
        assertAt(ready)
        val homeEntry = nav.currentBackStackEntry!!.id
        session.value = SessionState.Unavailable
        compose.waitForIdle()
        compose.onNodeWithTag(OFFLINE_TAG).assertIsDisplayed()
        assertEquals(homeEntry, nav.currentBackStackEntry!!.id)
        session.value = ready
        compose.waitForIdle()
        compose.onNodeWithTag(OFFLINE_TAG).assertDoesNotExist()
        assertAt(ready)
        assertEquals(homeEntry, nav.currentBackStackEntry!!.id)
        assertTrue(homeComposed)
    }

    @Test
    fun offlineAfterReadyStillLetsYouSignOutAndLeave() {
        start(ready)
        session.value = SessionState.Unavailable
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.session_sign_out)).performClick()
        assertEquals(1, signedOut)
        session.value = SessionState.LoggedOut
        assertAt(SessionState.LoggedOut)
        compose.onNodeWithTag(OFFLINE_TAG).assertDoesNotExist()
    }

    @Test
    fun aRestoredHomeEntryWithLoadingShowsSplashAndThenRedirects() {
        val tester = StateRestorationTester(compose)
        session.value = ready
        homeComposed = false
        tester.setContent {
            ViolinStudioTheme {
                nav = rememberNavController()
                SessionNavHost(
                    session = session.value,
                    onSignOut = {},
                    navController = nav,
                    auth = { PlaceholderScreen("auth") },
                    verifyEmail = { PlaceholderScreen("verify_email") },
                    onboarding = { PlaceholderScreen("onboarding") },
                    consent = { PlaceholderScreen("consent") },
                    guardianWait = {
                        guardianWaitSeen += it
                        PlaceholderScreen("guardian_wait")
                    },
                    home = { navigation ->
                        homeComposed = true
                        PlaceholderScreen("home")
                        Column {
                            Button(onClick = navigation.onOpenSettings, modifier = Modifier.testTag("open_settings")) {
                                Text("s")
                            }
                            Button(onClick = navigation.onOpenTuner, modifier = Modifier.testTag("open_tuner")) {
                                Text("t")
                            }
                        }
                    },
                    tuner = { onBack ->
                        tunerComposed = true
                        PlaceholderScreen("tuner")
                        Button(onClick = onBack, modifier = Modifier.testTag("tuner_back")) { Text("b") }
                    },
                    settings = { onBack ->
                        settingsComposed = true
                        PlaceholderScreen("settings")
                        Button(onClick = onBack, modifier = Modifier.testTag("settings_back")) { Text("b") }
                    }
                )
            }
        }
        assertAt(ready)
        // Process death: the process comes back with the session still resolving.
        session.value = SessionState.Loading
        homeComposed = false
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertFalse("home composed while the session was Loading", homeComposed)
        compose.onNodeWithTag(SPLASH_TAG).assertIsDisplayed()
        session.value = SessionState.LoggedOut
        assertAt(SessionState.LoggedOut)
        assertFalse(homeComposed)
    }

    @Test
    fun aStaleParentalPendingIsNeverHandedToTheSlotOnceTheSessionHasLeftTheWait() {
        val waiting = SessionState.ParentalPending("t***@example.com", 1)
        start(waiting)
        assertAt(waiting)
        session.value = ready
        assertAt(ready)
        guardianWaitSeen.clear()
        compose.runOnUiThread { nav.navigate(GuardianWaitDestination) }
        assertAt(ready)
        assertTrue("slot composed with a stale pending: $guardianWaitSeen", guardianWaitSeen.isEmpty())
    }
}
