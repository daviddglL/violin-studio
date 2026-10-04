package com.violinstudio.ui.feature.guardian.viewmodel

import com.violinstudio.domain.feature.auth.usecase.isPlausibleEmail

object GuardianWaitReducer {
    fun reduce(state: GuardianWaitState, mutation: GuardianWaitMutation): GuardianWaitState = when (mutation) {
        is GuardianWaitMutation.SessionUpdated ->
            state.copy(emailMasked = mutation.emailMasked, sends = mutation.sends)
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
            notice = mutation.notice
        )
        GuardianWaitMutation.EmailRejected -> state.copy(isLoading = false, emailError = GuardianEmailError.INVALID)
        GuardianWaitMutation.OwnEmailRejected ->
            state.copy(isLoading = false, emailError = GuardianEmailError.OWN_EMAIL)
        GuardianWaitMutation.AlreadyApproved ->
            state.copy(isLoading = false, notice = GuardianWaitNotice.ALREADY_APPROVED)
        is GuardianWaitMutation.Failed ->
            state.copy(isLoading = false, error = mutation.error, retryAfterSeconds = mutation.retryAfterSeconds)
        GuardianWaitMutation.DeleteStarted -> state.copy(isDeleting = true, deleteError = null)
        GuardianWaitMutation.DeleteSucceeded -> state.copy(isDeleting = false)
        is GuardianWaitMutation.DeleteFailed -> state.copy(isDeleting = false, deleteError = mutation.error)
    }

    private fun submitNewEmail(state: GuardianWaitState): GuardianWaitState = when {
        !state.canSubmitNewEmail -> state
        !isPlausibleEmail(state.email.trim()) -> state.copy(emailError = GuardianEmailError.INVALID)
        else -> startLoading(state.copy(emailError = null))
    }

    private fun startLoading(state: GuardianWaitState) =
        state.copy(isLoading = true, error = null, retryAfterSeconds = null, notice = null, deleteError = null)
}
