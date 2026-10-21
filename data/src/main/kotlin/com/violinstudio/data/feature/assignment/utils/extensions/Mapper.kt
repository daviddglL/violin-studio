package com.violinstudio.data.feature.assignment.utils.extensions

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.violinstudio.data.feature.assignment.dto.AssignmentDto
import com.violinstudio.domain.feature.assignment.model.Assignment
import com.violinstudio.domain.feature.assignment.model.AssignmentDraft
import com.violinstudio.domain.feature.assignment.model.AssignmentStatus
import java.time.Instant

private fun Instant.toTimestamp() = Timestamp(epochSecond, nano)

/** Solo las claves que permiten las reglas: los campos vacios se omiten (las reglas deniegan `null`). */
fun AssignmentDraft.toCreateFields(teacherUid: String): Map<String, Any> = buildMap {
    put("teacherUid", teacherUid)
    put("title", title)
    description?.let { put("description", it) }
    dueAt?.let { put("dueAt", it.toTimestamp()) }
    put("status", "open")
    put("createdAt", FieldValue.serverTimestamp())
    put("updatedAt", FieldValue.serverTimestamp())
}

/** Solo titulo, descripcion y `dueAt`; un valor `null` BORRA el campo (`FieldValue.delete()`, nunca `null`). */
fun AssignmentDraft.toUpdateFields(): Map<String, Any> = mapOf(
    "title" to title,
    "description" to (description ?: FieldValue.delete()),
    "dueAt" to (dueAt?.toTimestamp() ?: FieldValue.delete()),
    "updatedAt" to FieldValue.serverTimestamp()
)

/** Completar fija `doneAt` en el servidor; reabrir lo borra. */
fun doneFields(done: Boolean): Map<String, Any> = if (done) {
    mapOf("status" to "done", "doneAt" to FieldValue.serverTimestamp())
} else {
    mapOf("status" to "open", "doneAt" to FieldValue.delete())
}

/** `null` si falta el profesor o el titulo o el estado es desconocido; `createdAt`/`doneAt` pueden faltar mientras esta pendiente. */
fun AssignmentDto.toDomain(): Assignment? {
    val teacher = teacherUid ?: return null
    val cleanTitle = title ?: return null
    val state = when (status) {
        "open" -> AssignmentStatus.OPEN
        "done" -> AssignmentStatus.DONE
        else -> return null
    }
    return Assignment(
        id = id,
        teacherUid = teacher,
        title = cleanTitle,
        description = description,
        dueAt = dueAt,
        status = state,
        doneAt = doneAt.takeIf { state == AssignmentStatus.DONE },
        createdAt = createdAt,
        pendingSync = pendingSync
    )
}
