package com.violinstudio.ui.feature.chat.viewmodel

import com.violinstudio.domain.feature.chat.model.ChatRules

object ChatReducer {
    fun reduce(state: ChatState, mutation: ChatMutation): ChatState = when (mutation) {
        ChatMutation.Loading -> state.copy(isLoading = true, loadFailed = false, disabled = false)
        ChatMutation.Disabled -> state.copy(isLoading = false, disabled = true, messages = emptyList())
        is ChatMutation.Loaded -> state.copy(
            isLoading = false,
            loadFailed = false,
            myUid = mutation.myUid,
            messages = mutation.messages,
            // Si el mensaje a borrar ya no está (caducó o se borró en otro dispositivo) se cierra el diálogo.
            confirmingDelete = state.confirmingDelete?.takeIf { id -> mutation.messages.any { it.id == id } }
        )
        ChatMutation.LoadFailed -> state.copy(isLoading = false, loadFailed = true, messages = emptyList())
        // El campo no deja pasar de 1000 caracteres: el límite de las reglas.
        is ChatMutation.Draft -> state.copy(draft = mutation.text.take(ChatRules.MAX_TEXT), error = null)
        ChatMutation.Sending -> state.copy(isSending = true, error = null)
        ChatMutation.Sent -> state.copy(isSending = false, draft = "")
        is ChatMutation.Failed -> state.copy(isSending = false, confirmingDelete = null, error = mutation.error)
        is ChatMutation.ConfirmDelete -> state.copy(confirmingDelete = mutation.id, error = null)
    }
}
