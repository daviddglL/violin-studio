package com.violinstudio.ui.feature.teacher.viewmodel

import com.violinstudio.domain.feature.link.model.TeacherLink
import com.violinstudio.ui.commons.mvi.UiEffect
import com.violinstudio.ui.commons.mvi.UiIntent
import com.violinstudio.ui.commons.mvi.UiState

enum class StudentsError { Offline, TemporarilyUnavailable, Unknown }

data class StudentsState(
    /** Hasta la primera lectura fiable: la primera emisión vacía puede ser solo la caché. */
    val isLoading: Boolean = true,
    val students: List<TeacherLink> = emptyList(),

    /** Alumno cuya desvinculación se está confirmando. */
    val unlinkTarget: TeacherLink? = null,
    val isUnlinking: Boolean = false,
    val error: StudentsError? = null,

    /** El observador murió por algo que no es "sin alumnos": nunca se muestra el vacío en su lugar. */
    val loadFailed: Boolean = false
) : UiState {
    companion object {
        const val MAX_STUDENTS = 30
    }
}

sealed interface StudentsIntent : UiIntent {
    data class RequestUnlink(val link: TeacherLink) : StudentsIntent
    data object CancelUnlink : StudentsIntent
    data object ConfirmUnlink : StudentsIntent
    data object Retry : StudentsIntent
}

sealed interface StudentsEffect : UiEffect
