package com.violinstudio.data.feature.recording.dto

import com.violinstudio.domain.feature.recording.model.FailReason
import com.violinstudio.domain.feature.recording.model.MediaKind
import com.violinstudio.domain.feature.recording.model.QueueState
import com.violinstudio.domain.feature.recording.model.QueuedRecording
import com.violinstudio.domain.feature.recording.model.RecordedTake
import kotlinx.serialization.Serializable

/** Elemento de `u.{uid}.rec.queue` (JSON). Estado y motivo van por nombre; uno desconocido descarta el elemento. */
@Serializable
data class QueuedRecordingDto(
    val id: String,
    val uid: String,
    val path: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val consentEpoch: Long,
    val state: String,
    val attempts: Int = 0,
    val assignmentId: String? = null,
    val sharedWith: String? = null,
    val sessionUri: String? = null,
    val lastError: String? = null,
    /** 4c: ausente en las colas guardadas antes del video = audio. */
    val kind: String = MediaKind.AUDIO.name,
    val blurPolicyVersion: Int? = null
) {
    /** Sin ruta, ids ni `sessionUri` en logs o excepciones (REQ-AUD-03). */
    override fun toString(): String = "QueuedRecordingDto(state=$state, attempts=$attempts)"

    fun toDomain(): QueuedRecording? {
        val parsed = QueueState.entries.firstOrNull { it.name == state } ?: return null
        val media = MediaKind.entries.firstOrNull { it.name == kind } ?: return null
        return QueuedRecording(
            take = RecordedTake(id, uid, path, durationMs, sizeBytes, consentEpoch, media, blurPolicyVersion),
            assignmentId = assignmentId,
            sharedWith = sharedWith,
            state = parsed,
            attempts = attempts,
            sessionUri = sessionUri,
            lastError = lastError?.let { name -> FailReason.entries.firstOrNull { it.name == name } }
        )
    }

    companion object {
        fun from(item: QueuedRecording) = QueuedRecordingDto(
            id = item.take.id,
            uid = item.take.uid,
            path = item.take.path,
            durationMs = item.take.durationMs,
            sizeBytes = item.take.sizeBytes,
            consentEpoch = item.take.consentEpoch,
            state = item.state.name,
            attempts = item.attempts,
            assignmentId = item.assignmentId,
            sharedWith = item.sharedWith,
            sessionUri = item.sessionUri,
            lastError = item.lastError?.name,
            kind = item.take.kind.name,
            blurPolicyVersion = item.take.blurPolicyVersion
        )
    }
}
