package com.violinstudio.data.feature.practice.utils

import com.google.firebase.Timestamp
import com.violinstudio.data.feature.practice.dto.PracticeSessionDto
import java.time.Instant

/** Lee `practiceSessions/{id}` sin lanzar: lo ilegible queda en nulo y el mapper descarta el doc. */
object PracticeSessionParser {
    fun parse(id: String, data: Map<*, *>, pending: Boolean): PracticeSessionDto = PracticeSessionDto(
        id = id,
        startedAt = (data["startedAt"] as? Timestamp)?.asInstant(),
        durationSec = (data["durationSec"] as? Number)?.wholeInt(),
        instrument = data["instrument"] as? String,
        notes = data["notes"] as? String,
        pendingSync = pending
    )

    private fun Number.wholeInt(): Int? {
        val value = toDouble()
        val inRange = value in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()
        return if (value % 1.0 == 0.0 && inRange) value.toInt() else null
    }
}

private fun Timestamp.asInstant(): Instant = Instant.ofEpochSecond(seconds, nanoseconds.toLong())
