package com.violinstudio.ui.feature.chat.view

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.chat.model.ChatMessage
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.chat.viewmodel.ChatError
import com.violinstudio.ui.feature.chat.viewmodel.ChatIntent
import com.violinstudio.ui.feature.chat.viewmodel.ChatState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
// Pantalla de movil comun: en la de Robolectric por defecto (470dp de alto) solo cabe un mensaje en la lista.
@Config(application = Application::class, qualifiers = "es-w411dp-h891dp")
class ChatScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val sent = mutableListOf<ChatIntent>()
    private var back = 0
    private val mine = ChatMessage("m1", "u1", "Hola profe", null, pendingSync = true)
    private val theirs = ChatMessage("m2", "t1", "Practica la escala", null, pendingSync = false)
    private val loaded = ChatState(isLoading = false, myUid = "u1", messages = listOf(theirs, mine))

    private fun show(state: ChatState) {
        compose.setContent { ViolinStudioTheme { ChatScreen(state, { sent += it }, { back++ }) } }
    }

    @Test
    fun `muestra la conversacion y solo los propios se pueden borrar`() {
        show(loaded)
        compose.onNodeWithText("Practica la escala").assertIsDisplayed()
        compose.onNodeWithText("Hola profe").assertIsDisplayed()
        compose.onNodeWithText("Enviando…").assertIsDisplayed()
        compose.onNodeWithTag(chatDeleteTag("m2")).assertDoesNotExist()
        compose.onNodeWithTag(chatDeleteTag("m1")).performClick()
        compose.onNodeWithTag(CHAT_BACK_TAG).performClick()
        assertEquals(listOf<ChatIntent>(ChatIntent.RequestDelete("m1")), sent)
        assertEquals(1, back)
    }

    @Test
    fun `escribir emite el borrador y sin texto no se puede enviar`() {
        show(loaded)
        compose.onNodeWithTag(CHAT_SEND_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(CHAT_INPUT_TAG).performTextInput("hola")
        assertEquals(listOf<ChatIntent>(ChatIntent.DraftChanged("hola")), sent)
    }

    @Test
    fun `con borrador se envia`() {
        show(loaded.copy(draft = "hola"))
        compose.onNodeWithTag(CHAT_SEND_TAG).assertIsEnabled().performClick()
        assertEquals(listOf<ChatIntent>(ChatIntent.Send), sent)
    }

    @Test
    fun `sin mensajes lo dice y un error se muestra`() {
        show(loaded.copy(messages = emptyList(), error = ChatError.Denied))
        compose.onNodeWithTag(CHAT_EMPTY_TAG).assertIsDisplayed()
        compose.onNodeWithTag(CHAT_ERROR_TAG).assertIsDisplayed()
    }

    @Test
    fun `confirmar o cancelar el borrado`() {
        show(loaded.copy(confirmingDelete = "m1"))
        compose.onNodeWithTag(CHAT_DELETE_DIALOG_TAG).assertIsDisplayed()
        compose.onNodeWithTag(CHAT_DELETE_CANCEL_TAG).performClick()
        compose.onNodeWithTag(CHAT_DELETE_CONFIRM_TAG).performClick()
        assertEquals(listOf(ChatIntent.CancelDelete, ChatIntent.ConfirmDelete), sent)
    }

    @Test
    fun `apagado no ofrece escribir`() {
        show(ChatState(isLoading = false, disabled = true))
        compose.onNodeWithTag(CHAT_DISABLED_TAG).assertIsDisplayed()
        compose.onNodeWithTag(CHAT_INPUT_TAG).assertDoesNotExist()
    }

    @Test
    fun `si falla la carga se puede reintentar`() {
        show(ChatState(isLoading = false, loadFailed = true))
        compose.onNodeWithTag(CHAT_FAILED_TAG).assertIsDisplayed()
        compose.onNodeWithTag(CHAT_RETRY_TAG).performClick()
        assertEquals(listOf<ChatIntent>(ChatIntent.Retry), sent)
        compose.onNodeWithTag(CHAT_INPUT_TAG).assertDoesNotExist()
    }
}
