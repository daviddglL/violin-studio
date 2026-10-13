package com.violinstudio.ui.navigation

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.profile.model.ConsentStatus
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.profile.model.Role
import com.violinstudio.domain.feature.profile.model.UserProfile
import com.violinstudio.domain.feature.session.SessionState
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class RoleRoutesNavTest {
    @get:Rule
    val compose = createComposeRule()

    private fun ready(role: Role) = SessionState.Ready(
        UserProfile("u1", "Ana", Instrument.VIOLIN, "es", role, false, ConsentStatus.GRANTED, 1, null, false)
    )

    private val session = mutableStateOf<SessionState>(SessionState.Loading)
    private var redeemComposed = false
    private var myTeacherComposed = false
    private lateinit var nav: NavHostController

    private fun start(initial: SessionState) {
        session.value = initial
        redeemComposed = false
        myTeacherComposed = false
        compose.setContent {
            ViolinStudioTheme {
                nav = rememberNavController()
                SessionNavHost(
                    session = session.value,
                    onSignOut = {},
                    navController = nav,
                    auth = { Text("auth") },
                    home = { navigation ->
                        Column(Modifier.testTag("home")) {
                            navigation.onOpenRedeem?.let {
                                Button(onClick = it, modifier = Modifier.testTag("open_redeem")) { Text("r") }
                            }
                            navigation.onOpenMyTeacher?.let {
                                Button(onClick = it, modifier = Modifier.testTag("open_my_teacher")) { Text("t") }
                            }
                        }
                    },
                    redeemCode = { onBack ->
                        redeemComposed = true
                        Button(onClick = onBack, modifier = Modifier.testTag("redeem_screen")) { Text("b") }
                    },
                    myTeacher = { onBack ->
                        myTeacherComposed = true
                        Button(onClick = onBack, modifier = Modifier.testTag("my_teacher_screen")) { Text("b") }
                    }
                )
            }
        }
        compose.waitForIdle()
    }

    private fun assertAt(route: Any) {
        compose.waitForIdle()
        val here = nav.currentDestination
        assertTrue("expected $route but at $here", here?.hasRoute(route::class) == true)
    }

    @Test
    fun routesAreBusinessRoutesAndOutsideTheSessionAwareFeatures() {
        assertTrue(RedeemCodeDestination::class in BUSINESS_ROUTES)
        assertTrue(MyTeacherDestination::class in BUSINESS_ROUTES)
        assertFalse("link" in GatingIsolationTest.SESSION_AWARE_FEATURES)
    }

    @Test
    fun roleTableAllowsOnlyTheRightRole() {
        assertTrue(Role.INDEPENDENT.allows(RedeemCodeDestination::class))
        assertFalse(Role.STUDENT.allows(RedeemCodeDestination::class))
        assertFalse(Role.TEACHER.allows(RedeemCodeDestination::class))
        assertTrue(Role.STUDENT.allows(MyTeacherDestination::class))
        assertFalse(Role.INDEPENDENT.allows(MyTeacherDestination::class))
        assertFalse(Role.TEACHER.allows(MyTeacherDestination::class))
        assertTrue(Role.TEACHER.allows(HomeDestination::class))
    }

    @Test
    fun homeOffersRedeemToIndependentOnly() {
        start(ready(Role.INDEPENDENT))
        compose.onNodeWithTag("open_redeem").assertIsDisplayed()
        compose.onNodeWithTag("open_my_teacher").assertDoesNotExist()
    }

    @Test
    fun homeOffersMyTeacherToStudentOnly() {
        start(ready(Role.STUDENT))
        compose.onNodeWithTag("open_my_teacher").assertIsDisplayed()
        compose.onNodeWithTag("open_redeem").assertDoesNotExist()
    }

    @Test
    fun homeOffersNeitherToTeacher() {
        start(ready(Role.TEACHER))
        compose.onNodeWithTag("open_redeem").assertDoesNotExist()
        compose.onNodeWithTag("open_my_teacher").assertDoesNotExist()
    }

    @Test
    fun independentReachesRedeemAndBackReturnsHome() {
        start(ready(Role.INDEPENDENT))
        compose.onNodeWithTag("open_redeem").performClick()
        assertAt(RedeemCodeDestination)
        compose.onNodeWithTag("redeem_screen").performClick()
        assertAt(HomeDestination)
    }

    @Test
    fun studentDeepLinkToRedeemRedirectsHomeWithoutComposingIt() {
        start(ready(Role.STUDENT))
        compose.runOnUiThread { nav.navigate(RedeemCodeDestination) }
        assertAt(HomeDestination)
        assertFalse(redeemComposed)
    }

    @Test
    fun independentDeepLinkToMyTeacherRedirectsHomeWithoutComposingIt() {
        start(ready(Role.INDEPENDENT))
        compose.runOnUiThread { nav.navigate(MyTeacherDestination) }
        assertAt(HomeDestination)
        assertFalse(myTeacherComposed)
    }

    @Test
    fun roleChangeWhileOnTheRouteRedirectsHome() {
        start(ready(Role.STUDENT))
        compose.onNodeWithTag("open_my_teacher").performClick()
        assertAt(MyTeacherDestination)
        session.value = ready(Role.INDEPENDENT)
        assertAt(HomeDestination)
        assertEquals(null, nav.previousBackStackEntry)
    }

    @Test
    fun withoutReadyTheRoutesRedirectToTheStateRoot() {
        start(SessionState.LoggedOut)
        compose.runOnUiThread { nav.navigate(RedeemCodeDestination) }
        assertAt(AuthDestination)
        compose.runOnUiThread { nav.navigate(MyTeacherDestination) }
        assertAt(AuthDestination)
        assertFalse(redeemComposed || myTeacherComposed)
    }
}
