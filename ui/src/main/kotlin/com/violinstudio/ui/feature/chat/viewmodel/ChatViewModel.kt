package com.violinstudio.ui.feature.chat.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.chat.failure.ChatFailure
import com.violinstudio.domain.feature.chat.usecase.DeleteChatMessageUseCase
import com.violinstudio.domain.feature.chat.usecase.IsChatEnabledUseCase
import com.violinstudio.domain.feature.chat.usecase.ObserveChatUseCase
import com.violinstudio.domain.feature.chat.usecase.SendChatMessageUseCase
import com.violinstudio.ui.commons.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Chat del par que llega por la ruta (`teacherUid`, `studentUid`). Primero mira el interruptor del servidor: apagado
 * o ilegible, no se lee ni se escribe nada. Las reglas exigen el vínculo, el consentimiento y que el usuario sea uno de
 * los dos; si deniegan, la pantalla muestra el fallo y se puede reintentar. Sin logs ni texto en errores (lección 8).
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val isChatEnabled: IsChatEnabledUseCase,
    private val observeChat: ObserveChatUseCase,
    private val sendMessage: SendChatMessageUseCase,
    private val deleteMessage: DeleteChatMessageUseCase
) : MviViewModel<ChatState, ChatIntent, ChatEffect>(ChatState()) {
    private val teacherUid: String? = savedStateHandle.get<String>(TEACHER_UID_KEY)
    private val studentUid: String? = savedStateHandle.get<String>(STUDENT_UID_KEY)
    private var observer: Job? = null

    init {
        start()
    }

    private fun start() {
        observer?.cancel()
        reduce(ChatMutation.Loading)
        val teacher = teacherUid ?: return reduce(ChatMutation.LoadFailed)
        val student = studentUid ?: return reduce(ChatMutation.LoadFailed)
        observer = viewModelScope.launch {
            try {
                val enabled = isChatEnabled().getOrElse { return@launch reduce(ChatMutation.LoadFailed) }
                if (!enabled) return@launch reduce(ChatMutation.Disabled)
                observeChat(teacher, student).collect { reduce(ChatMutation.Loaded(it.myUid, it.messages)) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                reduce(ChatMutation.LoadFailed)
            }
        }
    }

    override suspend fun handleIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.DraftChanged -> reduce(ChatMutation.Draft(intent.text))
            ChatIntent.Send -> send()
            is ChatIntent.RequestDelete -> requestDelete(intent.id)
            ChatIntent.CancelDelete -> reduce(ChatMutation.ConfirmDelete(null))
            ChatIntent.ConfirmDelete -> confirmDelete()
            ChatIntent.Retry -> if (state.value.loadFailed) start()
        }
    }

    private suspend fun send() {
        val now = state.value
        if (!now.canSend) return
        reduce(ChatMutation.Sending)
        val result = guarded { sendMessage(teacherUid!!, studentUid!!, now.draft) }
        reduce(result.fold({ ChatMutation.Sent }, { ChatMutation.Failed(it.toChatError()) }))
    }

    private fun requestDelete(id: String) {
        val now = state.value
        // Solo los mensajes propios; las reglas lo repiten.
        if (now.messages.none { it.id == id && it.senderUid == now.myUid }) return
        reduce(ChatMutation.ConfirmDelete(id))
    }

    private suspend fun confirmDelete() {
        val id = state.value.confirmingDelete ?: return
        reduce(ChatMutation.ConfirmDelete(null))
        guarded { deleteMessage(id) }.onFailure { reduce(ChatMutation.Failed(it.toChatError())) }
    }

    private suspend fun guarded(block: suspend () -> Result<Unit>): Result<Unit> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        Result.failure(ChatFailure.Unknown)
    }

    private fun reduce(mutation: ChatMutation) = setState { ChatReducer.reduce(this, mutation) }

    companion object {
        /** Nombres de los argumentos de `ChatDestination`. */
        const val TEACHER_UID_KEY = "teacherUid"
        const val STUDENT_UID_KEY = "studentUid"
    }
}

private fun Throwable.toChatError(): ChatError = when (this) {
    ChatFailure.TooLong -> ChatError.TooLong
    ChatFailure.PermissionDenied, ChatFailure.NoSession -> ChatError.Denied
    else -> ChatError.Unknown
}
