package com.violinstudio.ui.feature.auth.viewmodel

object LoginReducer {
    fun reduce(state: LoginState, mutation: LoginMutation): LoginState = when (mutation) {
        is LoginMutation.EmailChanged -> state.copy(email = mutation.value, emailError = null, error = null)
        is LoginMutation.PasswordChanged -> state.copy(password = mutation.value, passwordError = null, error = null)
        LoginMutation.SubmitRequested -> {
            val emailError = if (state.email.isBlank()) LoginFieldError.EMAIL_EMPTY else null
            val passwordError = if (state.password.isEmpty()) LoginFieldError.PASSWORD_EMPTY else null
            state.copy(
                emailError = emailError,
                passwordError = passwordError,
                error = null,
                isLoading = emailError == null && passwordError == null
            )
        }
        LoginMutation.Succeeded -> state.copy(isLoading = false)
        is LoginMutation.Failed -> state.copy(isLoading = false, error = mutation.error)
    }
}
