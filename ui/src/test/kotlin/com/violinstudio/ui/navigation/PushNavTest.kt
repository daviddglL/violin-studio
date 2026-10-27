package com.violinstudio.ui.navigation

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.profile.model.ConsentStatus
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.profile.model.Role
import com.violinstudio.domain.feature.profile.model.UserProfile
import com.violinstudio.domain.feature.session.SessionFeatures
import com.violinstudio.domain.feature.session.SessionState
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class PushNavTest {
    @get:Rule
    val compose = createComposeRule()

    private fun ready(role: Role, features: SessionFeatures = SessionFeatures(recordings = true, chat = true)) =
        SessionState.Ready(
            UserProfile("u1", "Ana", Instrument.VIOLIN, "es", role, false, ConsentStatus.GRANTED, 1, null, false),
            features
        )

    private val session = mutableStateOf<SessionState>(SessionState.Loading)
    private val pushKind = mutableStateOf<String?>(null)
    private var handled = 0
    private lateinit var nav: NavHostController

    private fun start(initial: SessionState, kind: String?) {
        session.value = initial
        pushKind.value = kind
        handled = 0
        compose.setContent {
            ViolinStudioTheme {
                nav = rememberNavController()
                SessionNavHost(
                    session = session.value,
                    onSignOut = {},
                    navController = nav,
                    auth = { Text("auth") },
                    consent = { Text("consent") },
                    home = { Text("home") },
                    assignments = { _, _ -> Text("assignments") },
                    recordings = { _, _ -> Text("recordings") },
                    myTeacher = { _, _ -> Text("my teacher") },
                    students = { _, _, _ -> Text("students") },
                    pushKind = pushKind.value,
                    onPushHandled = {
                        handled++
                        pushKind.value = null
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
    fun eachKindMapsToItsScreenForTheRole() {
        val student = ready(Role.STUDENT)
        val teacher = ready(Role.TEACHER)
        val independent = ready(Role.INDEPENDENT)
        assertEquals(MyTeacherDestination, student.pushDestination("chat"))
        assertEquals(StudentsDestination, teacher.pushDestination("chat"))
        assertNull(independent.pushDestination("chat"))
        assertEquals(RecordingsDestination, student.pushDestination("review"))
        assertEquals(RecordingsDestination, independent.pushDestination("review"))
        assertEquals(AssignmentsDestination, student.pushDestination("assignment"))
        assertNull(teacher.pushDestination("assignment"))
        assertEquals(PracticeLogDestination, student.pushDestination(PUSH_KIND_PRACTICE))
        assertEquals(PracticeLogDestination, teacher.pushDestination(PUSH_KIND_PRACTICE))
        assertNull(student.pushDestination("otro"))
        assertNull(student.pushDestination(null))
    }

    @Test
    fun switchedOffFeaturesStayHome() {
        val off = ready(Role.STUDENT, SessionFeatures())
        assertNull(off.pushDestination("chat"))
        assertNull(off.pushDestination("review"))
        assertEquals(AssignmentsDestination, off.pushDestination("assignment"))
    }

    @Test
    fun tapOpensTheScreenOverHomeOnce() {
        start(ready(Role.STUDENT), "assignment")
        assertAt(AssignmentsDestination)
        assertEquals(1, handled)
        assertTrue(nav.previousBackStackEntry?.destination?.hasRoute(HomeDestination::class) == true)
    }

    @Test
    fun waitsForReadyBeforeOpening() {
        start(SessionState.Loading, "review")
        assertEquals(0, handled)
        session.value = ready(Role.STUDENT)
        assertAt(RecordingsDestination)
        assertEquals(1, handled)
    }

    @Test
    fun unavailableRouteIsDiscardedAndStaysHome() {
        start(ready(Role.TEACHER), "assignment")
        assertAt(HomeDestination)
        assertEquals(1, handled)
    }

    @Test
    fun tapWhileTheAppIsOpenOpensTheScreen() {
        start(ready(Role.TEACHER), null)
        assertAt(HomeDestination)
        pushKind.value = "chat"
        assertAt(StudentsDestination)
        assertEquals(1, handled)
    }
}
