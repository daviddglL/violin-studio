package com.violinstudio.data.feature.recording.dto

import com.violinstudio.domain.feature.recording.model.FailReason
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
    val lastError: String? = null
) {
    fun toDomain(): QueuedRecording? {
        val parsed = QueueState.entries.firstOrNull { it.name == state } ?: return null
        return QueuedRecording(
            take = RecordedTake(id, uid, path, durationMs, sizeBytes, consentEpoch),
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
            lastError = item.lastError?.name
        )
    }
}
