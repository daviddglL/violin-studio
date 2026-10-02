package com.violinstudio.ui.feature.auth.viewmodel

object VerifyEmailReducer {
    fun reduce(state: VerifyEmailState, mutation: VerifyEmailMutation): VerifyEmailState = when (mutation) {
        VerifyEmailMutation.CheckStarted -> state.copy(checking = true, message = null)
        VerifyEmailMutation.CheckedStillUnverified ->
            state.copy(checking = false, message = VerifyEmailMessage.NOT_VERIFIED_YET)
        VerifyEmailMutation.CheckedVerified -> state.copy(checking = false, message = null)
        is VerifyEmailMutation.CheckFailed -> state.copy(checking = false, message = mutation.message)
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
        is VerifyEmailMutation.CooldownTick -> state.copy(resendCooldownSeconds = mutation.remainingSeconds)
    }
}
