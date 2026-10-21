package com.violinstudio.data.feature.recording.utils

import com.violinstudio.domain.feature.recording.model.FailReason
import com.violinstudio.domain.feature.recording.model.FinalizeStatus

/**
 * Traduce el doc `users/{uid}/recordings/{id}` que escribe `finalizeRecording`. Sin doc, o con un estado que esta
 * version no conoce, la toma sigue esperando (nunca se borra una copia local por un dato que no se entiende).
 */
object FinalizeStatusParser {
    fun parse(exists: Boolean, data: Map<*, *>?): FinalizeStatus {
        if (!exists || data == null) return FinalizeStatus.Pending
        return when (data["status"]) {
            "ready" -> FinalizeStatus.Ready
            "rejected" -> rejected(data["rejectReason"] as? String)
            else -> FinalizeStatus.Pending
        }
    }

    // Motivos de `functions/src/recordings/validate.ts` (RejectReason) y finalize (`quota`, `internal`).
    private fun rejected(reason: String?): FinalizeStatus = when (reason) {
        "consent" -> FinalizeStatus.ConsentRejected
        "quota" -> FinalizeStatus.Rejected(FailReason.QUOTA)
        "size", "duration" -> FinalizeStatus.Rejected(FailReason.SIZE)
        "owner", "disabled", "assignment", "sharedWith" -> FinalizeStatus.Rejected(FailReason.PERMISSION)
        else -> FinalizeStatus.Rejected(FailReason.UNKNOWN)
    }
}
