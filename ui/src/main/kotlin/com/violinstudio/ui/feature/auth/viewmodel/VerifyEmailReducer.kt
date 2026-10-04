package com.violinstudio.ui.feature.auth.viewmodel

object VerifyEmailReducer {
    fun reduce(state: VerifyEmailState, mutation: VerifyEmailMutation): VerifyEmailState = when (mutation) {
        // Mientras el reenvío está bloqueado por el servidor, comprobar no borra ese aviso.
        VerifyEmailMutation.CheckStarted ->
            state.copy(checking = true, message = if (state.resendBlocked) state.message else null)
        VerifyEmailMutation.CheckedStillUnverified -> state.copy(
            checking = false,
            message = if (state.resendBlocked) state.message else VerifyEmailMessage.NOT_VERIFIED_YET
        )
        // La sesión reemplazará la pantalla en cuanto avance; hasta entonces se mantiene el aviso y las acciones
        // bloqueadas.
        VerifyEmailMutation.CheckedVerified ->
            state.copy(checking = false, verified = true, message = VerifyEmailMessage.VERIFIED_CONTINUING)
        VerifyEmailMutation.VerifiedTimedOut -> state.copy(verified = false, message = VerifyEmailMessage.UNKNOWN)
        is VerifyEmailMutation.CheckFailed -> state.copy(
            checking = false,
            message = if (state.resendBlocked) state.message else mutation.message
        )
        VerifyEmailMutation.ResendSent ->
            state.copy(resendCooldownSeconds = RESEND_COOLDOWN_SECONDS, message = VerifyEmailMessage.RESEND_SENT)
        is VerifyEmailMutation.ResendFailed -> state.copy(
            message = mutation.message,
            // Un límite del servidor se respeta sin reintentar solo: se bloquea el reenvío el mismo tiempo.
            resendCooldownSeconds = if (mutation.message == VerifyEmailMessage.WAIT_TOO_MANY_REQUESTS) {
                RESEND_COOLDOWN_SECONDS
            } else {
                state.resendCooldownSeconds
            }
        )
        is VerifyEmailMutation.DeleteActiveChanged -> state.copy(deleteActive = mutation.active)
        is VerifyEmailMutation.CooldownTick -> state.copy(
            resendCooldownSeconds = mutation.remainingSeconds,
            message = if (mutation.remainingSeconds == 0 && state.resendBlockedMessage) {
                null
            } else {
                state.message
            }
        )
    }

    private val VerifyEmailState.resendBlockedMessage: Boolean
        get() = message == VerifyEmailMessage.WAIT_TOO_MANY_REQUESTS

    private val VerifyEmailState.resendBlocked: Boolean
        get() = message == VerifyEmailMessage.WAIT_TOO_MANY_REQUESTS && resendCooldownSeconds > 0
}
