package com.violinstudio.ui.feature.home.view

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.home.viewmodel.HealthStatus
import com.violinstudio.ui.feature.home.viewmodel.HomeIntent
import com.violinstudio.ui.feature.home.viewmodel.HomeState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class) // evita arrancar Hilt/Firebase en tests de UI
class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(state: HomeState, onIntent: (HomeIntent) -> Unit = {}) {
        compose.setContent { ViolinStudioTheme { HomeScreen(state = state, onIntent = onIntent) } }
    }

    @Test
    fun pulsarElBotonEnviaCheckHealth() {
        val sent = mutableListOf<HomeIntent>()
        show(HomeState()) { sent += it }
        compose.onNodeWithText("Comprobar servidor").assertIsEnabled().performClick()
        assertEquals(listOf(HomeIntent.CheckHealth), sent)
    }

    @Test
    fun elBotonEstaDeshabilitadoMientrasCarga() {
        compose.mainClock.autoAdvance = false // el indicador infinito nunca deja la UI inactiva
        show(HomeState(HealthStatus.Loading))
        compose.onNodeWithText("Comprobar servidor").assertIsNotEnabled()
    }

    @Test
    fun okMuestraLaVersion() {
        show(HomeState(HealthStatus.Ok("0.1.0")))
        compose.onNodeWithTag("health_ok").assertIsDisplayed()
        compose.onNodeWithText("Servidor OK · v0.1.0").assertIsDisplayed()
    }

    @Test
    fun errorSinMensajeMuestraErrorDesconocido() {
        show(HomeState(HealthStatus.Error(null)))
        compose.onNodeWithText("Error desconocido").assertIsDisplayed()
    }

    @Test
    fun laTarjetaDelAfinadorNavegaAlAfinador() {
        var opened = 0
        compose.setContent {
            ViolinStudioTheme {
                val navigation = HomeNavigation(onOpenSettings = {}, onOpenTuner = { opened++ }, onOpenMetronome = {}, onOpenPractice = {})
                HomeScreen(HomeState(), {}, navigation = navigation)
            }
        }
        compose.onNodeWithTag("home_tuner").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun laTarjetaDelMetronomoNavegaAlMetronomo() {
        var opened = 0
        compose.setContent {
            ViolinStudioTheme {
                val navigation = HomeNavigation(onOpenSettings = {}, onOpenTuner = {}, onOpenMetronome = { opened++ }, onOpenPractice = {})
                HomeScreen(HomeState(), {}, navigation = navigation)
            }
        }
        compose.onNodeWithTag("home_metronome").performClick()
        assertEquals(1, opened)
    }

    @Test
    @Config(qualifiers = "es-w360dp-h300dp")
    fun enUnaPantallaBajaLaTarjetaDelMetronomoSePuedeAlcanzar() {
        compose.setContent {
            ViolinStudioTheme {
                val navigation = HomeNavigation(onOpenSettings = {}, onOpenTuner = {}, onOpenMetronome = {}, onOpenPractice = {})
                HomeScreen(HomeState(), {}, navigation = navigation)
            }
        }
        compose.onNodeWithTag("home_metronome").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun laTarjetaDePracticaNavegaAlRegistroDePractica() {
        var opened = 0
        compose.setContent {
            ViolinStudioTheme {
                val navigation = HomeNavigation(
                    onOpenSettings = {}, onOpenTuner = {}, onOpenMetronome = {}, onOpenPractice = { opened++ }
                )
                HomeScreen(HomeState(), {}, navigation = navigation)
            }
        }
        compose.onNodeWithTag("home_practice").performScrollTo().performClick()
        assertEquals(1, opened)
    }
}
