package com.violinstudio.ui.feature.intonation.view

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.intonation.model.IntonationExercises
import com.violinstudio.domain.feature.intonation.model.IntonationSummary
import com.violinstudio.domain.feature.intonation.model.Tendency
import com.violinstudio.domain.feature.tuner.model.Note
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.intonation.viewmodel.IntonationIntent
import com.violinstudio.ui.feature.intonation.viewmodel.IntonationMic
import com.violinstudio.ui.feature.intonation.viewmodel.IntonationState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "es")
class IntonationScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val exercise = IntonationExercises.byId("d_major_one_octave")!!
    private val sent = mutableListOf<IntonationIntent>()
    private var starts = 0
    private var back = 0

    private fun show(state: IntonationState) {
        compose.setContent {
            ViolinStudioTheme { IntonationScreen(state, { sent += it }, { starts++ }, { back++ }) }
        }
    }

    @Test
    fun `cada ejercicio tiene titulo propio`() {
        IntonationExercises.all.forEach { assertEquals(false, exerciseTitle(it.id) == exerciseTitle("nope")) }
    }

    @Test
    fun `muestra la nota que toca, la desviacion y empieza, salta y vuelve`() {
        show(IntonationState(exercise = exercise, index = 2, expected = Note(66), cents = -7.4))
        compose.onNodeWithText("Escala de re mayor (una octava)").assertIsDisplayed()
        compose.onNodeWithText("Nota 3 de 15").assertIsDisplayed()
        compose.onNodeWithText("Toca F#4").assertIsDisplayed()
        compose.onNodeWithText("-7 ¢").assertIsDisplayed()
        compose.onNodeWithText("Continuar").performScrollTo().performClick()
        assertEquals(1, starts)
        compose.onNodeWithTag(INTONATION_SKIP_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(INTONATION_BACK_TAG).performScrollTo().performClick()
        assertEquals(listOf<IntonationIntent>(IntonationIntent.Skip), sent)
        assertEquals(1, back)
    }

    @Test
    fun `escuchando se puede pausar`() {
        show(IntonationState(exercise = exercise, expected = Note(62), isListening = true))
        compose.onNodeWithText("Escuchando…").assertIsDisplayed()
        compose.onNodeWithTag(INTONATION_PAUSE_TAG).performScrollTo().performClick()
        assertEquals(listOf<IntonationIntent>(IntonationIntent.Pause), sent)
    }

    @Test
    fun `permiso bloqueado`() {
        show(IntonationState(exercise = exercise, expected = Note(62), mic = IntonationMic.BLOCKED))
        compose.onNodeWithTag(INTONATION_SETTINGS_TAG).performScrollTo().performClick()
        assertEquals(listOf<IntonationIntent>(IntonationIntent.OpenAppSettings), sent)
    }

    @Test
    fun `al acabar muestra puntuacion, mejor marca, consejos y repetir`() {
        val summary = IntonationSummary(15, 14, 9, 78, listOf(Tendency(1, 22), Tendency(11, -16)))
        show(IntonationState(exercise = exercise, summary = summary, best = 78, isNewBest = true))
        compose.onNodeWithTag(INTONATION_SCORE_TAG).assertIsDisplayed()
        compose.onNodeWithText("Puntuación: 78/100").assertIsDisplayed()
        compose.onNodeWithText("9 de 15 notas afinadas").assertIsDisplayed()
        compose.onNodeWithText("¡Tu mejor puntuación!").assertIsDisplayed()
        compose.onNodeWithText("Tiendes a tocar C# alto (+22 cents): mueve ese dedo un poco hacia la voluta.")
            .assertIsDisplayed()
        compose.onNodeWithText("Tiendes a tocar B bajo (−16 cents): mueve ese dedo un poco hacia el puente.")
            .assertIsDisplayed()
        compose.onNodeWithTag(INTONATION_RESTART_TAG).performScrollTo().performClick()
        assertEquals(listOf<IntonationIntent>(IntonationIntent.Restart), sent)
    }

    @Test
    fun `sin ninguna nota reconocida lo explica`() {
        show(IntonationState(exercise = exercise, summary = IntonationSummary(15, 0, 0, 0, emptyList()), best = 40))
        compose.onNodeWithText("No se reconoció ninguna nota. Prueba en un sitio sin ruido.").assertIsDisplayed()
        compose.onNodeWithText("Tu mejor puntuación: 40/100").assertIsDisplayed()
    }

    @Test
    fun `un ejercicio desconocido lo dice`() {
        show(IntonationState())
        compose.onNodeWithText("Este ejercicio ya no existe.").assertIsDisplayed()
    }
}
