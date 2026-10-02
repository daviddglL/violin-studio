package com.violinstudio.ui.feature.auth.viewmodel

import com.violinstudio.domain.feature.auth.usecase.isPlausibleEmail

object RegisterReducer {
    fun reduce(state: RegisterState, mutation: RegisterMutation): RegisterState = when (mutation) {
        is RegisterMutation.EmailChanged ->
            state.copy(email = mutation.value, emailError = null, error = null, succeeded = false)
        is RegisterMutation.PasswordChanged ->
            state.copy(password = mutation.value, passwordError = null, error = null, succeeded = false)
        RegisterMutation.SubmitRequested -> {
            val email = state.email.trim()
            val emailError = when {
                email.isEmpty() -> RegisterFieldError.EMAIL_EMPTY
                !isPlausibleEmail(email) -> RegisterFieldError.EMAIL_INVALID
                else -> null
            }
            val passwordError = when {
                state.password.isEmpty() -> RegisterFieldError.PASSWORD_EMPTY
                state.password.length < MIN_PASSWORD_LENGTH -> RegisterFieldError.PASSWORD_TOO_SHORT
                else -> null
            }
            state.copy(
                emailError = emailError,
                passwordError = passwordError,
                error = null,
                isLoading = emailError == null && passwordError == null
            )
        }
        RegisterMutation.Succeeded -> state.copy(isLoading = false, succeeded = true, password = "")
        is RegisterMutation.Rejected ->
            state.copy(isLoading = false, emailError = mutation.emailError, passwordError = mutation.passwordError)
        is RegisterMutation.Failed -> state.copy(isLoading = false, error = mutation.error)
    }
}
