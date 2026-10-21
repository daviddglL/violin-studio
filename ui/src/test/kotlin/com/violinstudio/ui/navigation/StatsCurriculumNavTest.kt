package com.violinstudio.ui.navigation

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import com.violinstudio.ui.feature.session.view.PlaceholderScreen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Estadisticas y lecciones: rutas de negocio comunes a todos los roles, solo con Ready. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class StatsCurriculumNavTest {
    @get:Rule
    val compose = createComposeRule()

    private val session = mutableStateOf<SessionState>(SessionState.LoggedOut)
    private lateinit var nav: NavHostController
    private var statsComposed = false
    private var curriculumComposed = false

    private fun ready(role: Role) = SessionState.Ready(
        UserProfile("u1", "Ana", Instrument.VIOLIN, "es", role, false, ConsentStatus.GRANTED, 1, null, false)
    )

    private fun start(initial: SessionState) {
        session.value = initial
        compose.setContent {
            ViolinStudioTheme {
                nav = rememberNavController()
                SessionNavHost(
                    session = session.value,
                    onSignOut = {},
                    navController = nav,
                    auth = { PlaceholderScreen("auth") },
                    home = { navigation ->
                        Column {
                            Button(onClick = navigation.onOpenStats!!, modifier = Modifier.testTag("open_stats")) {
                                Text("e")
                            }
                            Button(
                                onClick = navigation.onOpenCurriculum!!,
                                modifier = Modifier.testTag("open_lessons")
                            ) {
                                Text("l")
                            }
                        }
                    },
                    metronome = { PlaceholderScreen("metronome") },
                    tuner = { PlaceholderScreen("tuner") },
                    stats = { back ->
                        statsComposed = true
                        Button(onClick = back, modifier = Modifier.testTag("stats_back")) { Text("b") }
                    },
                    curriculum = { navigation ->
                        curriculumComposed = true
                        Column {
                            Button(
                                onClick = navigation.onOpenMetronome,
                                modifier = Modifier.testTag("lesson_metronome")
                            ) {
                                Text("m")
                            }
                            Button(onClick = navigation.onOpenTuner, modifier = Modifier.testTag("lesson_tuner")) {
                                Text("t")
                            }
                        }
                    }
                )
            }
        }
        compose.waitForIdle()
    }

    private fun at(route: kotlin.reflect.KClass<*>) = nav.currentDestination?.hasRoute(route) == true

    @Test
    fun `todos los roles abren estadisticas y vuelven a Home`() {
        start(ready(Role.INDEPENDENT))
        for (role in Role.entries) {
            session.value = ready(role)
            compose.waitForIdle()
            compose.onNodeWithTag("open_stats").performClick()
            compose.waitForIdle()
            assertTrue(role.name, at(StatsDestination::class))
            compose.onNodeWithTag("stats_back").performClick()
            compose.waitForIdle()
            assertTrue(role.name, at(HomeDestination::class))
        }
    }

    @Test
    fun `una leccion abre el metronomo y el afinador`() {
        start(ready(Role.STUDENT))
        compose.onNodeWithTag("open_lessons").performClick()
        compose.waitForIdle()
        assertTrue(at(CurriculumDestination::class))
        compose.onNodeWithTag("lesson_metronome").performClick()
        compose.waitForIdle()
        assertTrue(at(MetronomeDestination::class))
        compose.runOnUiThread { nav.popBackStack() }
        compose.waitForIdle()
        compose.onNodeWithTag("lesson_tuner").performClick()
        compose.waitForIdle()
        assertTrue(at(TunerDestination::class))
    }

    @Test
    fun `sin Ready las rutas nuevas redirigen y nunca se componen`() {
        start(SessionState.LoggedOut)
        compose.runOnUiThread { nav.navigate(StatsDestination) }
        compose.waitForIdle()
        compose.runOnUiThread { nav.navigate(CurriculumDestination) }
        compose.waitForIdle()
        assertTrue(at(AuthDestination::class))
        assertFalse(statsComposed || curriculumComposed)
    }
}
