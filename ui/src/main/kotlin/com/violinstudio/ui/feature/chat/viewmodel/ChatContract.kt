package com.violinstudio.ui.feature.chat.viewmodel

import com.violinstudio.domain.feature.chat.model.ChatMessage
import com.violinstudio.ui.commons.mvi.UiEffect
import com.violinstudio.ui.commons.mvi.UiIntent
import com.violinstudio.ui.commons.mvi.UiState

/** Fallos que ve el usuario al enviar o borrar; sin detalle (lección 8). */
enum class ChatError { TooLong, Denied, Unknown }

/** Chat de un par profesor-alumno. Los mensajes caducan a los 180 días; el autor puede borrar los suyos. */
data class ChatState(
    val isLoading: Boolean = true,
    /** El servidor tiene el chat apagado (`CHAT_ENABLED`): ni se lee ni se escribe. */
    val disabled: Boolean = false,
    val loadFailed: Boolean = false,
    val myUid: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    val isSending: Boolean = false,
    val error: ChatError? = null,
    /** Mensaje propio pendiente de confirmar su borrado. */
    val confirmingDelete: String? = null
) : UiState {
    val canSend: Boolean get() = !disabled && !loadFailed && !isLoading && !isSending && draft.isNotBlank()

    // Ni texto ni uids en volcados.
    override fun toString(): String =
        "ChatState(isLoading=$isLoading, disabled=$disabled, loadFailed=$loadFailed, messages=${messages.size}, " +
            "draft=${draft.length}, isSending=$isSending, error=$error)"
}

sealed interface ChatIntent : UiIntent {
    data class DraftChanged(val text: String) : ChatIntent {
        override fun toString(): String = "DraftChanged(${text.length})"
    }

    data object Send : ChatIntent

    data class RequestDelete(val id: String) : ChatIntent

    data object CancelDelete : ChatIntent

    data object ConfirmDelete : ChatIntent

    data object Retry : ChatIntent
}

sealed interface ChatEffect : UiEffect

sealed interface ChatMutation {
    data object Loading : ChatMutation

    data object Disabled : ChatMutation

    data class Loaded(val myUid: String, val messages: List<ChatMessage>) : ChatMutation

    data object LoadFailed : ChatMutation

    data class Draft(val text: String) : ChatMutation

    data object Sending : ChatMutation

    data object Sent : ChatMutation

    data class Failed(val error: ChatError) : ChatMutation

    data class ConfirmDelete(val id: String?) : ChatMutation
}
