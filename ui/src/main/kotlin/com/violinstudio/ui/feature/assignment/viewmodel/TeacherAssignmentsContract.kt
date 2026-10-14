package com.violinstudio.ui.feature.assignment.viewmodel

import com.violinstudio.domain.feature.assignment.model.Assignment
import com.violinstudio.ui.commons.mvi.UiEffect
import com.violinstudio.ui.commons.mvi.UiIntent
import com.violinstudio.ui.commons.mvi.UiState

/** Aviso que la pantalla muestra hasta [TeacherAssignmentsIntent.DismissNotice]. */
enum class TeacherAssignmentsNotice {
    /** Una tarea creada sin conexión desapareció sin haberla borrado: las reglas rechazaron la escritura. */
    REJECTED
}

enum class TeacherAssignmentsError { PERMISSION_DENIED, UNKNOWN }

/** Tareas que el profesor creó para un alumno. La creación y la edición viven en `AssignmentEditViewModel`. */
data class TeacherAssignmentsState(
    /** Hasta la primera lectura fiable: la primera emisión vacía puede ser solo la caché. */
    val isLoading: Boolean = true,
    /** Cada tarea lleva `pendingSync` (escritura local sin confirmar) para mostrar la insignia. */
    val assignments: List<Assignment> = emptyList(),
    /** El uid de la ruta falta o el vínculo cayó: nada que mostrar. */
    val unavailable: Boolean = false,
    /** El observador murió por algo que no es "sin datos": nunca se muestra el vacío en su lugar. */
    val loadFailed: Boolean = false,
    val deleteTarget: Assignment? = null,
    val isDeleting: Boolean = false,
    val error: TeacherAssignmentsError? = null,
    val notice: TeacherAssignmentsNotice? = null
) : UiState {
    // Títulos y descripciones son datos del alumno/profesor: fuera de cualquier volcado del estado.
    override fun toString(): String =
        "TeacherAssignmentsState(isLoading=$isLoading, assignments=${assignments.size}, unavailable=$unavailable, " +
            "loadFailed=$loadFailed, isDeleting=$isDeleting, error=$error, notice=$notice)"
}

sealed interface TeacherAssignmentsIntent : UiIntent {
    data class RequestDelete(val id: String) : TeacherAssignmentsIntent

    data object CancelDelete : TeacherAssignmentsIntent

    data object ConfirmDelete : TeacherAssignmentsIntent

    data object DismissError : TeacherAssignmentsIntent

    data object DismissNotice : TeacherAssignmentsIntent

    data object Retry : TeacherAssignmentsIntent
}

sealed interface TeacherAssignmentsEffect : UiEffect {
    /** El vínculo se perdió: la vista ya está vacía y se vuelve a la lista de alumnos. */
    data object NavigateBack : TeacherAssignmentsEffect
}
