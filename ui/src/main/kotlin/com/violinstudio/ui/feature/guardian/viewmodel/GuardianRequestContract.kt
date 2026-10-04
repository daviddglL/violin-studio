package com.violinstudio.ui.feature.guardian.viewmodel

import com.violinstudio.domain.feature.session.ConsentReason
import com.violinstudio.ui.commons.mvi.UiIntent
import com.violinstudio.ui.commons.mvi.UiState
import com.violinstudio.ui.feature.consent.viewmodel.ConsentDeleteError

/** Fallos de la solicitud al tutor. Las respuestas del servidor son genéricas: nunca se dice si el email existe. */
enum class GuardianRequestError {
    NETWORK,

    /** La cuenta no es de un menor (el servidor lo decide): no se puede pedir permiso a un tutor. */
    NOT_MINOR,

    /** Demasiados envíos; [GuardianRequestState.retryAfterSeconds] dice cuánto esperar si el servidor lo informó. */
    RATE_LIMITED,

    /** Fallo terminal (sin perfil, email sin verificar, menor no permitido): reintentar no lo arregla. */
    UNAVAILABLE,
    UNKNOWN
}

/** Error del campo del email del tutor: formato inválido o rechazado por el servidor (p. ej. igual al propio). */
enum class GuardianEmailError { INVALID, OWN_EMAIL }

data class GuardianRequestState(
    val email: String = "",
    val emailError: GuardianEmailError? = null,
    val reason: ConsentReason = ConsentReason.FIRST,
    val isLoading: Boolean = false,
    val error: GuardianRequestError? = null,
    val retryAfterSeconds: Long? = null,
    val isDeleting: Boolean = false,
    val deleteError: ConsentDeleteError? = null,
    val succeeded: Boolean = false,

    /** El servidor ya tenía el consentimiento: no hay nada que enviar, la sesión continúa sola. */
    val alreadyApproved: Boolean = false
) : UiState {
    val canSubmit: Boolean get() = email.isNotBlank() && !isLoading && !succeeded && !isDeleting

    /** El email del tutor es un dato de un tercero: no sale en logs. */
    override fun toString(): String = "GuardianRequestState(loading=$isLoading, succeeded=$succeeded, error=$error)"
}

sealed interface GuardianRequestIntent : UiIntent {
    /** La sesión (`ConsentPending` de un menor) fija el motivo que cambia el texto. */
    data class SessionUpdated(val reason: ConsentReason) : GuardianRequestIntent
    data class EmailChanged(val email: String) : GuardianRequestIntent
    data object SubmitGuardianEmail : GuardianRequestIntent
    data object DeleteAccount : GuardianRequestIntent
    data object SignOut : GuardianRequestIntent
}

sealed interface GuardianRequestMutation {
    data class SessionUpdated(val reason: ConsentReason) : GuardianRequestMutation
    data class EmailChanged(val email: String) : GuardianRequestMutation
    data object SubmitRequested : GuardianRequestMutation
    data object Succeeded : GuardianRequestMutation
    data object EmailRejected : GuardianRequestMutation
    data object OwnEmailRejected : GuardianRequestMutation
    data object AlreadyApproved : GuardianRequestMutation
    data class Failed(val error: GuardianRequestError, val retryAfterSeconds: Long? = null) : GuardianRequestMutation
    data object DeleteStarted : GuardianRequestMutation
    data object DeleteSucceeded : GuardianRequestMutation
    data class DeleteFailed(val error: ConsentDeleteError) : GuardianRequestMutation
}
