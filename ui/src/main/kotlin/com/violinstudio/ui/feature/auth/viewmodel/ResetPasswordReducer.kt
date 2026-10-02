package com.violinstudio.ui.feature.auth.viewmodel

import com.violinstudio.domain.feature.auth.usecase.isPlausibleEmail

object ResetPasswordReducer {
    fun reduce(state: ResetPasswordState, mutation: ResetPasswordMutation): ResetPasswordState = when (mutation) {
        is ResetPasswordMutation.EmailChanged ->
            ResetPasswordState(email = mutation.value, isLoading = state.isLoading)
        ResetPasswordMutation.SubmitRequested -> {
            val email = state.email.trim()
            val emailError = when {
                email.isEmpty() -> ResetFieldError.EMAIL_EMPTY
                !isPlausibleEmail(email) -> ResetFieldError.EMAIL_INVALID
                else -> null
            }
            state.copy(emailError = emailError, sent = false, error = null, isLoading = emailError == null)
        }
        ResetPasswordMutation.ScreenLeft -> ResetPasswordState(email = state.email, isLoading = state.isLoading)
        ResetPasswordMutation.Sent -> state.copy(isLoading = false, sent = true, error = null)
        ResetPasswordMutation.EmailRejected ->
            state.copy(isLoading = false, sent = false, emailError = ResetFieldError.EMAIL_INVALID)
        is ResetPasswordMutation.Failed -> state.copy(isLoading = false, sent = false, error = mutation.error)
    }
}
