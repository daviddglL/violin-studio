package com.violinstudio.ui.feature.auth.viewmodel

object LoginReducer {
    fun reduce(state: LoginState, mutation: LoginMutation): LoginState = when (mutation) {
        is LoginMutation.EmailChanged ->
            state.copy(email = mutation.value, emailError = null, error = null, succeeded = false)
        is LoginMutation.PasswordChanged ->
            state.copy(password = mutation.value, passwordError = null, error = null, succeeded = false)
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
        LoginMutation.GoogleStarted -> state.copy(isLoading = true)
        LoginMutation.GoogleCancelled -> state.copy(isLoading = false)
        LoginMutation.ScreenLeft -> LoginState(email = state.email, isLoading = state.isLoading)
        LoginMutation.Succeeded -> state.copy(isLoading = false, succeeded = true, password = "")
        is LoginMutation.Failed -> state.copy(
            isLoading = false,
            error = mutation.error,
            password = if (mutation.error == LoginError.INVALID_CREDENTIALS) "" else state.password
        )
    }
}
