package com.violinstudio.domain.feature.assignment.model

import com.violinstudio.domain.feature.assignment.failure.AssignmentFailure
import java.time.Instant

enum class AssignmentStatus { OPEN, DONE }

/**
 * Tarea de `users/{studentUid}/assignments/{id}`. [createdAt] es `null` mientras la escritura local aún no tiene
 * `serverTimestamp`; [doneAt] solo existe si [status] es [AssignmentStatus.DONE]. [pendingSync]: sin confirmar.
 */
data class Assignment(
    val id: String,
    val teacherUid: String,
    val title: String,
    val description: String?,
    val dueAt: Instant?,
    val status: AssignmentStatus,
    val doneAt: Instant?,
    val createdAt: Instant?,
    val pendingSync: Boolean
) {
    override fun toString(): String = "Assignment(status=$status, pendingSync=$pendingSync)"
}

/** Tarea validada lista para escribir. Solo se crea con [create]; título y descripción ya van recortados. */
@ConsistentCopyVisibility
data class AssignmentDraft private constructor(
    val id: String,
    val title: String,
    val description: String?,
    val dueAt: Instant?
) {
    override fun toString(): String = "AssignmentDraft(hasDescription=${description != null}, dueAt=$dueAt)"

    companion object {
        /** [id] es un UUID generado en cliente (1..64). */
        fun create(id: String, title: String, description: String?, dueAt: Instant?): Result<AssignmentDraft> {
            if (id.isEmpty() || id.length > AssignmentRules.ID_MAX) return Result.failure(AssignmentFailure.InvalidId)
            val cleanTitle = AssignmentRules.title(title).getOrElse { return Result.failure(it) }
            val cleanDescription = AssignmentRules.description(description).getOrElse { return Result.failure(it) }
            return Result.success(AssignmentDraft(id, cleanTitle, cleanDescription, dueAt))
        }
    }
}

/** Espeja `validTitle`/`validAssignment` de `firestore.rules`: la longitud es `String.length` (UTF-16) en ambos. */
object AssignmentRules {
    const val TITLE_MAX = 120
    const val DESCRIPTION_MAX = 1000
    const val ID_MAX = 64

    /** Misma lista blanca que las reglas: letras, marcas, números, puntuación, símbolos y espacio (sin control, formato ni saltos). */
    private val titleChars = Regex("^[\\p{L}\\p{M}\\p{N}\\p{P}\\p{S} ]+$")

    /** Recorta (las reglas exigen `title == trim`); vacío, demasiado largo o con caracteres no permitidos falla. */
    fun title(raw: String): Result<String> {
        val trimmed = raw.trim()
        return when {
            trimmed.isEmpty() -> Result.failure(AssignmentFailure.TitleEmpty)
            trimmed.length > TITLE_MAX -> Result.failure(AssignmentFailure.TitleTooLong)
            !titleChars.matches(trimmed) -> Result.failure(AssignmentFailure.TitleInvalid)
            else -> Result.success(trimmed)
        }
    }

    /** Recorta; vacío -> `null` (se omite en el doc, nunca se escribe vacío). */
    fun description(raw: String?): Result<String?> {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() }
        return if (trimmed != null && trimmed.length > DESCRIPTION_MAX) {
            Result.failure(AssignmentFailure.DescriptionTooLong)
        } else {
            Result.success(trimmed)
        }
    }

    /**
     * Orden del alumno (REQ-ASG-06): abiertas primero por `dueAt` ascendente (sin `dueAt` al final, por `createdAt`;
     * sin `createdAt`, es decir pendientes, las últimas); luego las hechas, la más reciente primero.
     */
    fun sortForStudent(list: List<Assignment>): List<Assignment> {
        val open = list.filter { it.status == AssignmentStatus.OPEN }.sortedWith(
            compareBy<Assignment, Instant?>(nullsLast()) { it.dueAt }
                .thenBy(nullsLast()) { it.createdAt }
                .thenBy { it.id }
        )
        val done = list.filter { it.status == AssignmentStatus.DONE }.sortedWith(
            compareByDescending<Assignment, Instant?>(nullsFirst()) { it.doneAt }.thenBy { it.id }
        )
        return open + done
    }
}
