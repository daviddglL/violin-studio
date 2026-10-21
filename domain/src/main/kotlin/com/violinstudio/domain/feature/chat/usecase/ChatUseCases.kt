package com.violinstudio.domain.feature.chat.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.chat.failure.ChatFailure
import com.violinstudio.domain.feature.chat.model.ChatMessage
import com.violinstudio.domain.feature.chat.model.ChatRules
import com.violinstudio.domain.feature.chat.model.ChatThread
import com.violinstudio.domain.feature.chat.repository.ChatRepository
import com.violinstudio.domain.feature.consent.repository.ConsentRepository
import java.time.Clock
import java.time.Duration
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * Conversacion del par visto por el usuario actual; sin sesion no emite. Orden de llegada al servidor; los mensajes
 * aun sin `createdAt` (pendientes) van al final en el orden en que llegan del repositorio.
 */
class ObserveChatUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val repo: ChatRepository
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(teacherUid: String, studentUid: String): Flow<ChatThread> =
        auth.authUser.map { it?.uid }.distinctUntilChanged().flatMapLatest { uid ->
            if (uid == null) {
                emptyFlow()
            } else {
                repo.observe(teacherUid, studentUid).map { ChatThread(uid, it.sortedForChat()) }
            }
        }

    private fun List<ChatMessage>.sortedForChat(): List<ChatMessage> {
        val (dated, pending) = partition { it.createdAt != null }
        return dated.sortedBy { it.createdAt } + pending
    }
}

/**
 * Envia un mensaje como el usuario actual. El texto se recorta; vacio o de mas de 1000 caracteres ni sale del
 * dispositivo. La caducidad es `ahora + 180 dias` con el reloj del dispositivo (las reglas toleran 170..181).
 */
class SendChatMessageUseCase(
    private val auth: AuthRepository,
    private val repo: ChatRepository,
    private val clock: Clock,
    private val newId: () -> String
) {
    @Inject
    constructor(auth: AuthRepository, repo: ChatRepository, clock: Clock) :
        this(auth, repo, clock, { UUID.randomUUID().toString() })

    suspend operator fun invoke(teacherUid: String, studentUid: String, text: String): Result<Unit> {
        val body = text.trim()
        if (body.isEmpty()) return Result.failure(ChatFailure.Empty)
        if (body.length > ChatRules.MAX_TEXT) return Result.failure(ChatFailure.TooLong)
        val me = auth.authUser.first()?.uid ?: return Result.failure(ChatFailure.NoSession)
        val expireAt = clock.instant().plus(Duration.ofDays(ChatRules.RETENTION_DAYS))
        return repo.send(teacherUid, studentUid, me, newId(), body, expireAt)
    }
}

class DeleteChatMessageUseCase @Inject constructor(private val repo: ChatRepository) {
    suspend operator fun invoke(id: String): Result<Unit> = repo.delete(id)
}

/**
 * Interruptor del servidor (`CHAT_ENABLED`): apagado hasta que la politica describa el chat. Si la config no se
 * puede leer falla, y la pantalla no ofrece el chat (falla cerrado).
 */
class IsChatEnabledUseCase @Inject constructor(private val consent: ConsentRepository) {
    suspend operator fun invoke(): Result<Boolean> = consent.identityConfig().map { it.chatEnabled }
}
