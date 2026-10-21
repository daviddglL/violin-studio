package com.violinstudio.domain.feature.chat.repository

import com.violinstudio.domain.feature.chat.model.ChatMessage
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Mensajes de `chatMessages` del par [teacherUid]-[studentUid]. Las escrituras son offline-first (no esperan al
 * servidor): los fallos llegan como `Result.failure(ChatFailure)` y un rechazo posterior de las reglas retira el
 * mensaje de la cache. Los flujos fallan al recoger con `ChatFailure` (p. ej. `PermissionDenied` si cae el vinculo).
 */
interface ChatRepository {
    fun observe(teacherUid: String, studentUid: String): Flow<List<ChatMessage>>

    /** `createdAt` lo pone el servidor; [expireAt] es la caducidad (TTL) que exigen las reglas. */
    suspend fun send(
        teacherUid: String,
        studentUid: String,
        senderUid: String,
        id: String,
        text: String,
        expireAt: Instant
    ): Result<Unit>

    /** Solo el autor puede borrar un mensaje suyo (reglas). */
    suspend fun delete(id: String): Result<Unit>
}
