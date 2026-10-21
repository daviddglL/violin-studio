package com.violinstudio.data.feature.recording.utils

import com.google.firebase.Timestamp
import com.violinstudio.domain.feature.recording.model.RecordingSummary

/**
 * Lee `users/{uid}/recordings/{id}` (lo escribe `finalizeRecording`) sin lanzar. Un doc sin `status` reconocible se
 * descarta; los campos opcionales ilegibles quedan en nulo.
 */
object RecordingSummaryParser {
    private const val MS_PER_SEC = 1_000
    private const val NS_PER_MS = 1_000_000

    fun parse(id: String, data: Map<*, *>): RecordingSummary? {
        val ready = when (data["status"]) {
            "ready" -> true
            "rejected" -> false
            else -> return null
        }
        return RecordingSummary(
            id = id,
            createdAtMs = (data["createdAt"] as? Timestamp)
                ?.let { it.seconds * MS_PER_SEC + it.nanoseconds / NS_PER_MS },
            durationMs = (data["durationSec"] as? Number)?.toDouble()?.takeIf { it >= 0 }
                ?.let { (it * MS_PER_SEC).toLong() },
            ready = ready,
            isDemo = data["sharedWith"] is String
        )
    }
}
