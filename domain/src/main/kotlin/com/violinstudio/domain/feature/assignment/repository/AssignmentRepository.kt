package com.violinstudio.domain.feature.assignment.repository

import com.violinstudio.domain.feature.assignment.model.Assignment
import com.violinstudio.domain.feature.assignment.model.AssignmentDraft
import kotlinx.coroutines.flow.Flow

/**
 * Tareas en `users/{studentUid}/assignments`. Las escrituras son offline-first (no esperan al servidor): los fallos
 * llegan como `Result.failure(AssignmentFailure)` y un rechazo posterior de las reglas no se notifica aquí. Los
 * flujos fallan al recoger con `AssignmentFailure` (p. ej. `PermissionDenied` si cae el vínculo).
 */
interface AssignmentRepository {
    /** Todas las tareas del alumno. */
    fun observeForStudent(studentUid: String): Flow<List<Assignment>>

    /** Solo las creadas por [teacherUid] (las reglas exigen el filtro `teacherUid == me`). */
    fun observeByTeacher(teacherUid: String, studentUid: String): Flow<List<Assignment>>

    suspend fun create(studentUid: String, teacherUid: String, draft: AssignmentDraft): Result<Unit>

    /** Solo título, descripción y `dueAt`; el estado no se toca. */
    suspend fun update(studentUid: String, draft: AssignmentDraft): Result<Unit>

    /** Completar fija `doneAt` en el servidor; reabrir lo elimina. */
    suspend fun setDone(studentUid: String, id: String, done: Boolean): Result<Unit>

    suspend fun delete(studentUid: String, id: String): Result<Unit>
}
