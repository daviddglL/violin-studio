package com.violinstudio.domain.feature.chat.model

import java.time.Instant

/**
 * Mensaje del chat de un vinculo. [createdAt] lo fija el servidor: es nulo mientras el mensaje no ha llegado
 * ([pendingSync]). Ni el texto ni los uids salen en `toString`.
 */
data class ChatMessage(
    val id: String,
    val senderUid: String,
    val text: String,
    val createdAt: Instant?,
    val pendingSync: Boolean
) {
    override fun toString(): String = "ChatMessage(pendingSync=$pendingSync)"
}

/** Conversacion vista por [myUid]: los mensajes en orden de llegada (los pendientes, al final). */
data class ChatThread(val myUid: String, val messages: List<ChatMessage>) {
    override fun toString(): String = "ChatThread(messages=${messages.size})"
}

/** Limites que tambien imponen las reglas de Firestore (`validMessage`). */
object ChatRules {
    const val MAX_TEXT = 1_000

    /** El servidor borra cada mensaje a los 180 dias (TTL de `expireAt`); las reglas admiten 170..181 dias. */
    const val RETENTION_DAYS = 180L
}
