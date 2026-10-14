package com.violinstudio.ui.feature.teacher.viewmodel

import com.violinstudio.domain.feature.link.model.TeacherLink
import com.violinstudio.domain.feature.practice.model.PracticeSession
import com.violinstudio.ui.commons.mvi.UiEffect
import com.violinstudio.ui.commons.mvi.UiIntent
import com.violinstudio.ui.commons.mvi.UiState

/** Vista de solo lectura: nada de aquí escribe en el registro del alumno (REQ-TVW-04). */
data class StudentDetailState(
    /** Hasta la primera lectura fiable: la primera emisión vacía puede ser solo la caché. */
    val isLoading: Boolean = true,
    val link: TeacherLink? = null,
    val sessions: List<PracticeSession> = emptyList(),
    val weeklyTotalSec: Int = 0,

    /** El uid de la ruta no corresponde a ningún alumno vinculado. */
    val unavailable: Boolean = false,

    /** El observador murió por algo que no es "sin datos": nunca se muestra el vacío en su lugar. */
    val loadFailed: Boolean = false
) : UiState {
    // Nombre y notas del alumno son datos sensibles: fuera de cualquier volcado del estado.
    override fun toString(): String =
        "StudentDetailState(isLoading=$isLoading, sessions=${sessions.size}, unavailable=$unavailable, " +
            "loadFailed=$loadFailed)"
}

sealed interface StudentDetailIntent : UiIntent {
    data object Retry : StudentDetailIntent
}

sealed interface StudentDetailEffect : UiEffect {
    /** El vínculo se perdió (desvinculación): la vista ya está vacía y se vuelve a la lista. */
    data object NavigateBack : StudentDetailEffect
}
