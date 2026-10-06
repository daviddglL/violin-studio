package com.violinstudio.ui.feature.practice.view

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "es")
class PracticeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val sent = mutableListOf<PracticeIntent>()
    private val t0 = Instant.parse("2026-01-05T10:00:00Z")
    private val synced = PracticeSession("s1", t0, 3725, Instrument.VIOLIN, "escalas", false)
    private val pending = PracticeSession("s2", t0, 600, Instrument.CELLO, null, true)
    private val running = RunningSession("r1", t0, Instrument.VIOLIN)

    private fun show(state: PracticeState, onBack: () -> Unit = {}) {
        compose.setContent {
            ViolinStudioTheme { PracticeScreen(state, { sent += it }, onBack = onBack, zone = ZoneOffset.UTC) }
        }
    }

    @Test
    fun `sin historial muestra el vacio con la llamada a iniciar`() {
        show(PracticeState(ready = true))
        compose.onNodeWithTag(PRACTICE_EMPTY_TAG).assertIsDisplayed()
        compose.onNodeWithTag(PRACTICE_START_TAG).performClick()
        assertEquals(listOf<PracticeIntent>(PracticeIntent.Start), sent)
    }

    @Test
    fun `con sesion en curso muestra el cronometro y parar envia Stop`() {
        show(PracticeState(ready = true, running = running, elapsedSec = 3725))
        compose.onNodeWithTag(PRACTICE_TIMER_TAG).assertTextEquals("1:02:05")
        compose.onNodeWithTag(PRACTICE_START_TAG).assertDoesNotExist()
        compose.onNodeWithTag(PRACTICE_STOP_TAG).performClick()
        assertEquals(listOf<PracticeIntent>(PracticeIntent.Stop), sent)
    }

    @Test
    fun `el historial marca la sesion pendiente de sincronizar y no la ya sincronizada`() {
        show(PracticeState(ready = true, history = listOf(synced, pending), weeklyTotalSec = 4325))
        compose.onNodeWithTag(practiceItemTag("s1")).assertIsDisplayed()
        compose.onNodeWithTag(practiceItemPendingTag("s2")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(practiceItemPendingTag("s1")).assertDoesNotExist()
        compose.onNodeWithTag(PRACTICE_WEEKLY_TAG).assertIsDisplayed()
        compose.onNodeWithTag(PRACTICE_EMPTY_TAG).assertDoesNotExist()
    }

    @Test
    fun `el dialogo de guardar muestra el contador de notas y envia sus intents`() {
        show(PracticeState(ready = true, running = running, showSave = true, draftNotes = "hola"))
        compose.onNodeWithTag(PRACTICE_NOTES_COUNTER_TAG, useUnmergedTree = true).assertTextEquals("4/500")
        compose.onNodeWithTag(PRACTICE_SAVE_TAG).assertIsEnabled().performClick()
        compose.onNodeWithTag(PRACTICE_DISCARD_TAG).performClick()
        compose.onNodeWithTag(PRACTICE_CONTINUE_TAG).performClick()
        compose.onNodeWithTag(PRACTICE_NOTES_FIELD_TAG).performTextReplacement("nuevo")
        assertEquals(
            listOf(
                PracticeIntent.Save,
                PracticeIntent.Discard,
                PracticeIntent.DismissSave,
                PracticeIntent.EditNotes("nuevo")
            ),
            sent
        )
    }

    @Test
    fun `con mas de 500 caracteres Guardar esta desactivado`() {
        show(PracticeState(ready = true, running = running, showSave = true, draftNotes = "a".repeat(501)))
        compose.onNodeWithTag(PRACTICE_NOTES_COUNTER_TAG, useUnmergedTree = true).assertTextEquals("501/500")
        compose.onNodeWithTag(PRACTICE_SAVE_TAG).assertIsNotEnabled()
    }

    @Test
    fun `borrar pide confirmacion y confirmar o cancelar envia su intent`() {
        show(PracticeState(ready = true, history = listOf(synced), confirmDeleteId = "s1"))
        compose.onNodeWithTag(PRACTICE_DELETE_DIALOG_TAG).assertIsDisplayed()
        compose.onNodeWithTag(PRACTICE_DELETE_CONFIRM_TAG).performClick()
        compose.onNodeWithTag(PRACTICE_DELETE_CANCEL_TAG).performClick()
        assertEquals(listOf(PracticeIntent.ConfirmDelete, PracticeIntent.CancelDelete), sent)
    }

    @Test
    fun `el boton borrar de una sesion solo la solicita`() {
        show(PracticeState(ready = true, history = listOf(synced)))
        compose.onNodeWithTag(practiceItemDeleteTag("s1")).performScrollTo().performClick()
        assertEquals(listOf<PracticeIntent>(PracticeIntent.RequestDelete("s1")), sent)
    }

    /** REQ-PRA-08: de una sesion guardada solo se edita `notes`; duracion, inicio e instrumento no. */
    @Test
    fun `editar ofrece unicamente las notas`() {
        show(PracticeState(ready = true, history = listOf(synced)))
        compose.onNodeWithTag(practiceItemEditTag("s1")).performScrollTo().performClick()
        compose.onNodeWithTag(PRACTICE_EDIT_DIALOG_TAG).assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onNodeWithTag(PRACTICE_EDIT_FIELD_TAG).performTextReplacement("arpegios")
        compose.onNodeWithTag(PRACTICE_EDIT_SAVE_TAG).performClick()
        assertEquals(listOf<PracticeIntent>(PracticeIntent.UpdateNotes("s1", "arpegios")), sent)
    }

    @Test
    fun `editar y cancelar no envia nada`() {
        show(PracticeState(ready = true, history = listOf(synced)))
        compose.onNodeWithTag(practiceItemEditTag("s1")).performScrollTo().performClick()
        compose.onNodeWithTag(PRACTICE_EDIT_CANCEL_TAG).performClick()
        assertEquals(emptyList<PracticeIntent>(), sent)
        compose.onNodeWithTag(PRACTICE_EDIT_DIALOG_TAG).assertDoesNotExist()
    }

    @Test
    fun `el selector de instrumento envia la eleccion`() {
        show(PracticeState(ready = true))
        compose.onNodeWithTag(practiceInstrumentTag("cello")).performClick()
        assertEquals(listOf<PracticeIntent>(PracticeIntent.SelectInstrument(Instrument.CELLO)), sent)
    }

    @Test
    fun `el selector no se ofrece con la sesion en curso`() {
        show(PracticeState(ready = true, running = running))
        compose.onNodeWithTag(practiceInstrumentTag("cello")).assertDoesNotExist()
    }

    @Test
    fun `un aviso se muestra y se descarta`() {
        show(PracticeState(ready = true, message = PracticeMessage.TOO_SHORT))
        compose.onNodeWithTag(PRACTICE_MESSAGE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(PRACTICE_MESSAGE_DISMISS_TAG).performClick()
        assertEquals(listOf<PracticeIntent>(PracticeIntent.DismissMessage), sent)
    }

    @Test
    fun `muestra el titulo y atras vuelve`() {
        var back = 0
        show(PracticeState(ready = true), onBack = { back++ })
        compose.onNodeWithText("Práctica").assertIsDisplayed()
        compose.onNodeWithTag(PRACTICE_BACK_TAG).performClick()
        assertEquals(1, back)
    }
}
