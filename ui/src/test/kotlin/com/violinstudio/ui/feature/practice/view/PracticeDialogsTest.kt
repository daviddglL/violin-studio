package com.violinstudio.ui.feature.practice.view

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.practice.model.PracticeSession
import com.violinstudio.domain.feature.practice.model.RunningSession
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.practice.viewmodel.PracticeIntent
import com.violinstudio.ui.feature.practice.viewmodel.PracticeMessage
import com.violinstudio.ui.feature.practice.viewmodel.PracticeState
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Revisión P3b: fallos visibles en los diálogos, reintento honesto, edición que no pierde el texto, busy. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "es")
class PracticeDialogsTest {
    @get:Rule
    val compose = createComposeRule()

    private val sent = mutableListOf<PracticeIntent>()
    private val t0 = Instant.parse("2026-01-05T10:00:00Z")
    private val synced = PracticeSession("s1", t0, 3725, Instrument.VIOLIN, "escalas", false)
    private val running = RunningSession("r1", t0, Instrument.VIOLIN)
    private var state by mutableStateOf(PracticeState(ready = true))

    private fun show(initial: PracticeState) {
        state = initial
        compose.setContent {
            ViolinStudioTheme { PracticeScreen(state, { sent += it }, onBack = {}, zone = ZoneOffset.UTC) }
        }
    }

    private fun openEdit() {
        compose.onNodeWithTag(practiceItemEditTag("s1")).performScrollTo().performClick()
    }

    @Test
    fun `un fallo al guardar se ve dentro del dialogo de guardar`() {
        show(PracticeState(ready = true, running = running, showSave = true, message = PracticeMessage.UNKNOWN))
        compose.onNodeWithTag(PRACTICE_SAVE_MESSAGE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(PRACTICE_MESSAGE_TAG).assertDoesNotExist()
    }

    @Test
    fun `Reintentar solo se ofrece para fallos de flujo`() {
        show(PracticeState(ready = true, message = PracticeMessage.UNKNOWN, retryable = false))
        compose.onNodeWithTag(PRACTICE_RETRY_TAG).assertDoesNotExist()
        state = PracticeState(ready = true, message = PracticeMessage.UNKNOWN, retryable = true)
        compose.onNodeWithTag(PRACTICE_RETRY_TAG).performClick()
        assertEquals(listOf<PracticeIntent>(PracticeIntent.Retry), sent)
    }

    @Test
    fun `con busy no se puede iniciar, parar, guardar ni descartar`() {
        show(PracticeState(ready = true, busy = true))
        compose.onNodeWithTag(PRACTICE_START_TAG).assertIsNotEnabled()
        state = PracticeState(ready = true, busy = true, running = running)
        compose.onNodeWithTag(PRACTICE_STOP_TAG).assertIsNotEnabled()
        state = PracticeState(ready = true, busy = true, running = running, showSave = true)
        compose.onNodeWithTag(PRACTICE_SAVE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(PRACTICE_DISCARD_TAG).assertIsNotEnabled()
    }

    @Test
    fun `en la edicion Guardar se desactiva con mas de 500 caracteres`() {
        show(PracticeState(ready = true, history = listOf(synced)))
        openEdit()
        compose.onNodeWithTag(PRACTICE_EDIT_FIELD_TAG).performTextReplacement("a".repeat(501))
        compose.onNodeWithTag(PRACTICE_EDIT_SAVE_TAG).assertIsNotEnabled()
    }

    @Test
    fun `si la edicion falla el dialogo sigue abierto con el texto y muestra el fallo`() {
        show(PracticeState(ready = true, history = listOf(synced)))
        openEdit()
        compose.onNodeWithTag(PRACTICE_EDIT_FIELD_TAG).performTextReplacement("arpegios")
        compose.onNodeWithTag(PRACTICE_EDIT_SAVE_TAG).performClick()
        assertEquals(listOf<PracticeIntent>(PracticeIntent.UpdateNotes("s1", "arpegios")), sent)
        state = state.copy(message = PracticeMessage.UNKNOWN)
        compose.onNodeWithTag(PRACTICE_EDIT_DIALOG_TAG).assertIsDisplayed()
        compose.onNodeWithTag(PRACTICE_EDIT_FIELD_TAG).assertTextContains("arpegios")
        compose.onNodeWithTag(PRACTICE_EDIT_MESSAGE_TAG).assertIsDisplayed()
    }

    @Test
    fun `la edicion se cierra cuando el historial refleja las notas nuevas`() {
        show(PracticeState(ready = true, history = listOf(synced)))
        openEdit()
        compose.onNodeWithTag(PRACTICE_EDIT_FIELD_TAG).performTextReplacement("arpegios")
        compose.onNodeWithTag(PRACTICE_EDIT_SAVE_TAG).performClick()
        compose.onNodeWithTag(PRACTICE_EDIT_DIALOG_TAG).assertIsDisplayed()
        state = state.copy(history = listOf(synced.copy(notes = "arpegios")))
        compose.onNodeWithTag(PRACTICE_EDIT_DIALOG_TAG).assertDoesNotExist()
    }

    @Test
    fun `guardar la edicion sin cambios cierra sin enviar nada`() {
        show(PracticeState(ready = true, history = listOf(synced)))
        openEdit()
        compose.onNodeWithTag(PRACTICE_EDIT_SAVE_TAG).performClick()
        assertEquals(emptyList<PracticeIntent>(), sent)
        compose.onNodeWithTag(PRACTICE_EDIT_DIALOG_TAG).assertDoesNotExist()
    }

    @Test
    fun `la edicion en curso sobrevive a la recreacion de la actividad`() {
        val tester = StateRestorationTester(compose)
        tester.setContent {
            ViolinStudioTheme {
                PracticeScreen(PracticeState(ready = true, history = listOf(synced)), {}, {}, zone = ZoneOffset.UTC)
            }
        }
        openEdit()
        compose.onNodeWithTag(PRACTICE_EDIT_FIELD_TAG).performTextReplacement("nuevo texto")
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag(PRACTICE_EDIT_DIALOG_TAG).assertIsDisplayed()
        compose.onNodeWithTag(PRACTICE_EDIT_FIELD_TAG).assertTextContains("nuevo texto")
    }

    @Test
    fun `el cronometro tiene descripcion de duracion`() {
        show(PracticeState(ready = true, running = running, elapsedSec = 3725))
        compose.onNodeWithTag(PRACTICE_TIMER_TAG).assertContentDescriptionEquals("1 h 2 min")
    }
}
