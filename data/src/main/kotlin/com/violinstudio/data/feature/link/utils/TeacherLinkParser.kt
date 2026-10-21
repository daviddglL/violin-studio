package com.violinstudio.data.feature.link.utils

import com.google.firebase.Timestamp
import com.violinstudio.data.feature.link.dto.TeacherLinkDto
import java.time.Instant

/** Lee `teacherLinks/{id}` sin lanzar: lo ilegible queda en nulo y el mapper descarta el doc. */
object TeacherLinkParser {
    fun parse(data: Map<*, *>): TeacherLinkDto = TeacherLinkDto(
        teacherUid = data["teacherUid"] as? String,
        studentUid = data["studentUid"] as? String,
        teacherDisplayName = data["teacherDisplayName"] as? String,
        studentDisplayName = data["studentDisplayName"] as? String,
        studentInstrument = data["studentInstrument"] as? String,
        ackVersion = (data["ackVersion"] as? Number)?.wholeInt(),
        createdAt = (data["createdAt"] as? Timestamp)?.asInstant()
    )

    private fun Number.wholeInt(): Int? {
        val value = toDouble()
        val inRange = value in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()
        return if (value % 1.0 == 0.0 && inRange) value.toInt() else null
    }
}

private fun Timestamp.asInstant(): Instant = Instant.ofEpochSecond(seconds, nanoseconds.toLong())
