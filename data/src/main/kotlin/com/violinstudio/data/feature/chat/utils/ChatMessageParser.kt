package com.violinstudio.data.feature.chat.utils

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreException.Code
import com.violinstudio.data.commons.firebase.RemoteDoc
import com.violinstudio.domain.feature.chat.failure.ChatFailure
import com.violinstudio.domain.feature.chat.model.ChatMessage
import java.time.Instant

/** Lee `chatMessages/{id}` sin lanzar: un doc sin autor o sin texto se descarta. */
object ChatMessageParser {
    fun parse(doc: RemoteDoc): ChatMessage? {
        val sender = doc.data["senderUid"] as? String ?: return null
        val text = doc.data["text"] as? String ?: return null
        return ChatMessage(
            id = doc.id,
            senderUid = sender,
            text = text,
            createdAt = (doc.data["createdAt"] as? Timestamp)?.let {
                Instant.ofEpochSecond(
                    it.seconds,
                    it.nanoseconds.toLong()
                )
            },
            pendingSync = doc.hasPendingWrites
        )
    }

    /** Campos exactos que piden las reglas (`validMessage`); `createdAt` es la hora del servidor. */
    fun createFields(
        teacherUid: String,
        studentUid: String,
        senderUid: String,
        text: String,
        expireAt: Instant
    ): Map<String, Any> = mapOf(
        "teacherUid" to teacherUid,
        "studentUid" to studentUid,
        "senderUid" to senderUid,
        "text" to text,
        "createdAt" to FieldValue.serverTimestamp(),
        "expireAt" to Timestamp(expireAt.epochSecond, expireAt.nano)
    )
}

object ChatErrorMapper {
    fun map(error: Throwable): ChatFailure = when {
        error is ChatFailure -> error
        error is FirebaseFirestoreException && error.code == Code.PERMISSION_DENIED -> ChatFailure.PermissionDenied
        else -> ChatFailure.Unknown
    }
}
