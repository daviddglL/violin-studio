package com.violinstudio.ui.feature.guardian.viewmodel

import com.violinstudio.domain.feature.auth.usecase.isPlausibleEmail

object GuardianWaitReducer {
    fun reduce(state: GuardianWaitState, mutation: GuardianWaitMutation): GuardianWaitState = when (mutation) {
        is GuardianWaitMutation.DeleteActiveChanged -> state.copy(deleteActive = mutation.active)
        is GuardianWaitMutation.SessionUpdated ->
            state.copy(
                emailMasked = mutation.emailMasked,
                sends = mutation.sends,
                notice = state.notice.takeIf { it != GuardianWaitNotice.ALREADY_APPROVED }
            )
        is GuardianWaitMutation.CanResend -> state.copy(canResend = mutation.value)
        GuardianWaitMutation.ResendRequested ->
            if (state.canResendNow) startLoading(state) else state
        GuardianWaitMutation.ChangeEmailStarted ->
            if (state.busy) {
                state
            } else {
                state.copy(
                    changingEmail = true,
                    email = "",
                    emailError = null,
                    error = null,
                    retryAfterSeconds = null,
                    notice = null
                )
            }
        GuardianWaitMutation.ChangeEmailCancelled ->
            if (state.busy) {
                state
            } else {
                state.copy(
                    changingEmail = false,
                    email = "",
                    emailError = null,
                    error = null,
                    retryAfterSeconds = null
                )
            }
        is GuardianWaitMutation.EmailChanged ->
            if (state.busy || !state.changingEmail) {
                state
            } else {
                state.copy(email = mutation.email, emailError = null, error = null, retryAfterSeconds = null)
            }
        GuardianWaitMutation.SubmitNewEmailRequested -> submitNewEmail(state)
        is GuardianWaitMutation.Succeeded -> state.copy(
            isLoading = false,
            changingEmail = false,
            email = "",
            canResend = true,
            emailMasked = mutation.emailMasked ?: state.emailMasked,
            sends = state.sends + 1,
            notice = mutation.notice
        )
        GuardianWaitMutation.EmailRejected -> rejected(state, GuardianEmailError.INVALID)
        GuardianWaitMutation.OwnEmailRejected -> rejected(state, GuardianEmailError.OWN_EMAIL)
        GuardianWaitMutation.AlreadyApproved ->
            state.copy(isLoading = false, notice = GuardianWaitNotice.ALREADY_APPROVED)
        is GuardianWaitMutation.Failed -> failed(state, mutation)
        GuardianWaitMutation.NoticeCleared -> state.copy(notice = null)
        GuardianWaitMutation.RetryWaitElapsed -> state.copy(
            resendBlocked = false,
            error = state.error.takeIf { it != GuardianRequestError.RATE_LIMITED },
            retryAfterSeconds = null
        )
        GuardianWaitMutation.SignOutStarted -> state.copy(isSigningOut = true)
        GuardianWaitMutation.SignOutFinished -> state.copy(isSigningOut = false)
    }

    /**
     * El servidor (o la comparacion local) rechazo la direccion. Si venia de un reenvio, la direccion recordada ya no
     * sirve: se abre el campo de cambio con el error en vez de dejar un fallo invisible.
     */
    private fun rejected(state: GuardianWaitState, error: GuardianEmailError) = state.copy(
        isLoading = false,
        emailError = error,
        canResend = state.canResend && state.changingEmail,
        changingEmail = true
    )

    private fun failed(state: GuardianWaitState, mutation: GuardianWaitMutation.Failed): GuardianWaitState {
        val terminal = mutation.error == GuardianRequestError.NOT_MINOR ||
            mutation.error == GuardianRequestError.UNAVAILABLE
        val wait = mutation.retryAfterSeconds
        return state.copy(
            isLoading = false,
            error = mutation.error,
            retryAfterSeconds = wait,
            // Reintentar un fallo terminal no lo arregla; con una espera pedida, tampoco hasta que pase.
            canResend = state.canResend && !terminal,
            resendBlocked = mutation.error == GuardianRequestError.RATE_LIMITED && wait != null && wait > 0
        )
    }

    private fun submitNewEmail(state: GuardianWaitState): GuardianWaitState = when {
        !state.canSubmitNewEmail -> state
        !isPlausibleEmail(state.email.trim()) -> state.copy(emailError = GuardianEmailError.INVALID)
        else -> startLoading(state.copy(emailError = null))
    }

    private fun startLoading(state: GuardianWaitState) =
        state.copy(isLoading = true, error = null, retryAfterSeconds = null, notice = null)
}
