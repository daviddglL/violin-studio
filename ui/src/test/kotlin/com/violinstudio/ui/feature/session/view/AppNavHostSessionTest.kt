package com.violinstudio.ui.feature.session.view

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.consent.model.IdentityConfig
import com.violinstudio.domain.feature.profile.model.ConsentStatus
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.profile.model.Role
import com.violinstudio.domain.feature.profile.model.UserProfile
import com.violinstudio.domain.feature.session.SessionState
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.navigation.HomeDestination
import com.violinstudio.ui.navigation.SessionNavHost
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
    private var signedOut = 0
    private lateinit var nav: NavHostController

    private fun start(initial: SessionState) {
        session.value = initial
        homeComposed = false
        compose.setContent {
            ViolinStudioTheme {
                nav = rememberNavController()
                SessionNavHost(
                    session = session.value,
                    onSignOut = { signedOut++ },
                    navController = nav,
                    home = {
                        homeComposed = true
                        PlaceholderScreen("home")
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
        compose.onNodeWithText("Cerrar sesión").performClick()
        assertEquals(1, signedOut)
    }

    @Test
    fun loadingShowsTheSplash() {
        start(SessionState.Loading)
        compose.onNodeWithTag(SPLASH_TAG).assertIsDisplayed()
    }
}
