package com.violinstudio.ui.feature.guardian.viewmodel

import com.violinstudio.ui.commons.mvi.UiIntent
import com.violinstudio.ui.commons.mvi.UiState
import com.violinstudio.ui.feature.consent.viewmodel.ConsentDeleteError

/** Resultado positivo que la pantalla de espera confirma con un mensaje cortés (nunca dice nada del tutor). */
enum class GuardianWaitNotice { RESENT, EMAIL_CHANGED, ALREADY_APPROVED }

/**
 * Espera del menor mientras su tutor confirma. De ese tutor solo se conoce [emailMasked] (lo que devuelve el servidor):
 * el email en claro nunca forma parte del estado; [canResend] solo dice si la app lo recuerda en memoria. El email
 * nuevo ([email]) lo teclea el propio menor.
 */
data class GuardianWaitState(
    val emailMasked: String? = null,
    val sends: Int = 0,
    val canResend: Boolean = false,
    val changingEmail: Boolean = false,
    val email: String = "",
    val emailError: GuardianEmailError? = null,
    val isLoading: Boolean = false,
    val error: GuardianRequestError? = null,
    val retryAfterSeconds: Long? = null,
    val notice: GuardianWaitNotice? = null,
    val isDeleting: Boolean = false,
    val isSigningOut: Boolean = false,

    /** El servidor pidio esperar ([retryAfterSeconds]): reenviar no se ofrece hasta que pase. */
    val resendBlocked: Boolean = false,
    val deleteError: ConsentDeleteError? = null
) : UiState {
    /** Mientras algo está en curso, o ya se aprobó, ninguna otra acción de envío está disponible. */
    val busy: Boolean get() = isLoading || isDeleting || isSigningOut || notice == GuardianWaitNotice.ALREADY_APPROVED
    val canResendNow: Boolean get() = canResend && !resendBlocked && !changingEmail && !busy
    val canSubmitNewEmail: Boolean get() = changingEmail && email.isNotBlank() && !busy

    override fun toString(): String = "GuardianWaitState(sends=$sends, loading=$isLoading, error=$error)"
}

sealed interface GuardianWaitIntent : UiIntent {
    /** La sesión (`ParentalPending`) entrega el email enmascarado y el número de envíos. */
    data class SessionUpdated(val emailMasked: String?, val sends: Int) : GuardianWaitIntent
    data object Resend : GuardianWaitIntent
    data object ChangeEmail : GuardianWaitIntent
    data object CancelChangeEmail : GuardianWaitIntent
    data class EmailChanged(val email: String) : GuardianWaitIntent {
        override fun toString(): String = "EmailChanged"
    }
    data object SubmitNewEmail : GuardianWaitIntent

    /** "Comprobar de nuevo": pide a la sesion resolverse otra vez (p. ej. tras ALREADY_APPROVED). */
    data object CheckAgain : GuardianWaitIntent
    data object RetryWaitElapsed : GuardianWaitIntent
    data object DeleteAccount : GuardianWaitIntent
    data object SignOut : GuardianWaitIntent
}

sealed interface GuardianWaitMutation {
    data class SessionUpdated(val emailMasked: String?, val sends: Int) : GuardianWaitMutation
    data class CanResend(val value: Boolean) : GuardianWaitMutation
    data object ResendRequested : GuardianWaitMutation
    data object ChangeEmailStarted : GuardianWaitMutation
    data object ChangeEmailCancelled : GuardianWaitMutation
    data class EmailChanged(val email: String) : GuardianWaitMutation {
        override fun toString(): String = "EmailChanged"
    }
    data object SubmitNewEmailRequested : GuardianWaitMutation
    data class Succeeded(val notice: GuardianWaitNotice) : GuardianWaitMutation
    data object EmailRejected : GuardianWaitMutation
    data object OwnEmailRejected : GuardianWaitMutation
    data object AlreadyApproved : GuardianWaitMutation
    data class Failed(val error: GuardianRequestError, val retryAfterSeconds: Long? = null) : GuardianWaitMutation
    data object NoticeCleared : GuardianWaitMutation
    data object RetryWaitElapsed : GuardianWaitMutation
    data object SignOutStarted : GuardianWaitMutation
    data object SignOutFinished : GuardianWaitMutation
    data object DeleteStarted : GuardianWaitMutation
    data object DeleteSucceeded : GuardianWaitMutation
    data class DeleteFailed(val error: ConsentDeleteError) : GuardianWaitMutation
}
