package com.violinstudio.data.feature.recording.utils

import com.google.firebase.Timestamp
import com.violinstudio.domain.feature.recording.model.Review
import com.violinstudio.domain.feature.recording.model.Verdict

/** Lee `reviews/{id}` sin lanzar; un doc sin veredicto valido se descarta. `reviewerUid: null` = anonimizada. */
object ReviewParser {
    private const val MS_PER_SEC = 1_000L
    private const val NS_PER_MS = 1_000_000

    fun parse(data: Map<*, *>?): Review? {
        data ?: return null
        val verdict = Verdict.fromWire(data["verdict"]) ?: return null
        return Review(
            verdict = verdict,
            text = data["text"] as? String ?: "",
            updatedAtMs = (data["updatedAt"] as? Timestamp)
                ?.let { it.seconds * MS_PER_SEC + it.nanoseconds / NS_PER_MS },
            fromTeacher = data["reviewerUid"] is String
        )
    }
}
