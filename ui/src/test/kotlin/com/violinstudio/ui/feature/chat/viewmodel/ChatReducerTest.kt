package com.violinstudio.ui.feature.chat.viewmodel

import com.violinstudio.domain.feature.chat.model.ChatMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatReducerTest {
    private val m1 = ChatMessage("m1", "u1", "hola", null, pendingSync = true)
    private val loaded = ChatState(isLoading = false, myUid = "u1", messages = listOf(m1))

    private fun ChatState.then(vararg mutations: ChatMutation) = mutations.fold(this, ChatReducer::reduce)

    @Test
    fun `cargar, apagado y fallo`() {
        val s = ChatState().then(ChatMutation.Loaded("u1", listOf(m1)))
        assertEquals(loaded, s)
        assertTrue(s.then(ChatMutation.Disabled).let { it.disabled && it.messages.isEmpty() && !it.isLoading })
        val failed = s.then(ChatMutation.LoadFailed)
        assertTrue(failed.loadFailed && failed.messages.isEmpty())
        assertFalse(failed.then(ChatMutation.Loading).loadFailed)
    }

    @Test
    fun `el borrador se corta a 1000 y limpia el error`() {
        val s = loaded.copy(error = ChatError.Unknown).then(ChatMutation.Draft("a".repeat(1_200)))
        assertEquals(1_000, s.draft.length)
        assertNull(s.error)
        assertTrue(s.canSend)
        assertFalse(s.then(ChatMutation.Draft("   ")).canSend)
    }

    @Test
    fun `enviar vacia el borrador y un fallo lo conserva`() {
        val sending = loaded.then(ChatMutation.Draft("hola"), ChatMutation.Sending)
        assertTrue(sending.isSending)
        assertFalse(sending.canSend)
        assertEquals("", sending.then(ChatMutation.Sent).draft)
        val failed = sending.then(ChatMutation.Failed(ChatError.Denied))
        assertEquals("hola", failed.draft)
        assertEquals(ChatError.Denied, failed.error)
        assertFalse(failed.isSending)
    }

    @Test
    fun `el dialogo de borrar se cierra si el mensaje desaparece o falla`() {
        val asking = loaded.then(ChatMutation.ConfirmDelete("m1"))
        assertEquals("m1", asking.confirmingDelete)
        assertEquals("m1", asking.then(ChatMutation.Loaded("u1", listOf(m1))).confirmingDelete)
        assertNull(asking.then(ChatMutation.Loaded("u1", emptyList())).confirmingDelete)
        assertNull(asking.then(ChatMutation.Failed(ChatError.Unknown)).confirmingDelete)
        assertNull(asking.then(ChatMutation.ConfirmDelete(null)).confirmingDelete)
    }

    @Test
    fun `ni el texto ni los uids salen en los volcados`() {
        val s = loaded.copy(draft = "secreto")
        assertFalse("secreto" in s.toString() || "u1" in s.toString() || "hola" in s.toString())
        assertFalse("secreto" in ChatIntent.DraftChanged("secreto").toString())
    }
}
