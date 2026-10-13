package com.violinstudio.ui.feature.link.viewmodel

import com.violinstudio.domain.feature.link.model.TeacherLink
import com.violinstudio.ui.commons.mvi.UiEffect
import com.violinstudio.ui.commons.mvi.UiIntent
import com.violinstudio.ui.commons.mvi.UiState

enum class MyTeacherError { Offline, TemporarilyUnavailable, Unknown }

data class MyTeacherState(
    /** Hasta la primera lectura fiable: la primera emisión vacía puede ser solo la caché, no "sin profesor". */
    val isLoading: Boolean = true,
    val teacher: TeacherLink? = null,
    val confirmingUnlink: Boolean = false,
    val isUnlinking: Boolean = false,
    val error: MyTeacherError? = null
) : UiState

sealed interface MyTeacherIntent : UiIntent {
    data object RequestUnlink : MyTeacherIntent
    data object CancelUnlink : MyTeacherIntent
    data object ConfirmUnlink : MyTeacherIntent
}

/** Sin efectos: el cambio de rol al desvincular lo resuelve la navegación. */
sealed interface MyTeacherEffect : UiEffect
