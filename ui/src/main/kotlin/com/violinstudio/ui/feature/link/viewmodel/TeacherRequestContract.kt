package com.violinstudio.ui.feature.link.viewmodel

import com.violinstudio.domain.feature.profile.model.TeacherRequestStatus
import com.violinstudio.ui.commons.mvi.UiEffect
import com.violinstudio.ui.commons.mvi.UiIntent
import com.violinstudio.ui.commons.mvi.UiState

/** Fallos de la solicitud tal y como se muestran. */
sealed interface TeacherRequestError {
    /** El servidor no lo admite ahora (menor, consentimiento no vigente, vinculado como alumno...). */
    data object NotEligible : TeacherRequestError
    data object Offline : TeacherRequestError
    data object Unknown : TeacherRequestError
}

data class TeacherRequestState(
    /** Estado conocido de la solicitud; `null` si no hay ninguna. */
    val status: TeacherRequestStatus? = null,
    val isSending: Boolean = false,
    val error: TeacherRequestError? = null
) : UiState {
    /** Solo sin solicitud viva: una pendiente o rechazada no se reenvia (el servidor tampoco la reescribiria). */
    val canSubmit: Boolean get() = status == null && !isSending && error != TeacherRequestError.NotEligible
}

sealed interface TeacherRequestIntent : UiIntent {
    data object Submit : TeacherRequestIntent
}

sealed interface TeacherRequestEffect : UiEffect
