package com.violinstudio.data.feature.assignment.utils

import com.google.firebase.Timestamp
import com.violinstudio.data.feature.assignment.dto.AssignmentDto
import java.time.Instant

/** Lee `assignments/{id}` sin lanzar: lo ilegible queda en nulo y el mapper decide si descarta el doc. */
object AssignmentParser {
    fun parse(id: String, data: Map<*, *>, pending: Boolean): AssignmentDto = AssignmentDto(
        id = id,
        teacherUid = data["teacherUid"] as? String,
        title = data["title"] as? String,
        description = data["description"] as? String,
        dueAt = (data["dueAt"] as? Timestamp)?.asInstant(),
        status = data["status"] as? String,
        doneAt = (data["doneAt"] as? Timestamp)?.asInstant(),
        createdAt = (data["createdAt"] as? Timestamp)?.asInstant(),
        pendingSync = pending
    )
}

private fun Timestamp.asInstant(): Instant = Instant.ofEpochSecond(seconds, nanoseconds.toLong())
