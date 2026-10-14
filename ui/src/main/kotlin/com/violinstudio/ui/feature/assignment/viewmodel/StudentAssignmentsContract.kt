package com.violinstudio.ui.feature.assignment.viewmodel

import com.violinstudio.domain.feature.assignment.model.Assignment
import com.violinstudio.ui.commons.mvi.UiEffect
import com.violinstudio.ui.commons.mvi.UiIntent
import com.violinstudio.ui.commons.mvi.UiState

enum class StudentAssignmentsError { PERMISSION_DENIED, UNKNOWN }

/** Tareas del alumno con sesión, ya en el orden de REQ-ASG-06 (abiertas primero, luego hechas). */
data class StudentAssignmentsState(
    /** Hasta la primera lectura fiable: la primera emisión vacía puede ser solo la caché. */
    val isLoading: Boolean = true,
    /** Cada tarea lleva `pendingSync` (cambio local sin confirmar) para mostrar la insignia. */
    val assignments: List<Assignment> = emptyList(),
    /** El observador murió por algo que no es "sin datos": nunca se muestra el vacío en su lugar. */
    val loadFailed: Boolean = false,
    /** Tareas con una escritura en curso: se ignoran más toques sobre ellas. */
    val busyIds: Set<String> = emptySet(),
    val error: StudentAssignmentsError? = null
) : UiState {
    // Títulos y descripciones: fuera de cualquier volcado del estado.
    override fun toString(): String =
        "StudentAssignmentsState(isLoading=$isLoading, assignments=${assignments.size}, loadFailed=$loadFailed, " +
            "busy=${busyIds.size}, error=$error)"
}

sealed interface StudentAssignmentsIntent : UiIntent {
    /** Marca hecha: el `doneAt` lo fija el servidor (`request.time`), el cliente no envía fecha. */
    data class Complete(val id: String) : StudentAssignmentsIntent

    data class Reopen(val id: String) : StudentAssignmentsIntent

    data object DismissError : StudentAssignmentsIntent

    data object Retry : StudentAssignmentsIntent
}

/** La pantalla no recibe efectos: avisos y errores viven en el estado. */
sealed interface StudentAssignmentsEffect : UiEffect
