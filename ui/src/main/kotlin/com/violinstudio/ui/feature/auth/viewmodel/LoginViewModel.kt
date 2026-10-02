package com.violinstudio.ui.feature.auth.viewmodel

import com.violinstudio.domain.feature.auth.failure.AuthFailure
import com.violinstudio.domain.feature.auth.usecase.SignInWithEmailUseCase
import com.violinstudio.ui.commons.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/**
 * No navega: tras un acceso correcto `authUser` re-emite y el host de navegación sustituye la pantalla. Ningún fallo
 * se registra ni se muestra con su causa (puede llevar el email).
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val signInWithEmail: SignInWithEmailUseCase
) : MviViewModel<LoginState, LoginIntent, LoginEffect>(LoginState()) {
    // Los intents van en serie: un segundo toque se atendería cuando el primero ya terminó. Se descarta al encolar.
    private var submitPending = false

    override fun onIntent(intent: LoginIntent) {
        if (intent == LoginIntent.Submit) {
            if (submitPending) return
            submitPending = true
        }
        super.onIntent(intent)
    }

    override suspend fun handleIntent(intent: LoginIntent) = when (intent) {
        is LoginIntent.EmailChanged -> reduce(LoginMutation.EmailChanged(intent.value))
        is LoginIntent.PasswordChanged -> reduce(LoginMutation.PasswordChanged(intent.value))
        LoginIntent.Submit -> onSubmit()
    }

    private suspend fun onSubmit() {
        try {
            reduce(LoginMutation.SubmitRequested)
            if (!state.value.isLoading) return
            val result = try {
                signInWithEmail(state.value.email, state.value.password)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                Result.failure(AuthFailure.Unknown())
            }
            result.fold(
                onSuccess = { reduce(LoginMutation.Succeeded) },
                onFailure = { reduce(LoginMutation.Failed(it.toLoginError())) }
            )
        } finally {
            submitPending = false
        }
    }

    // Credenciales erróneas, cuenta inexistente y email mal formado comparten mensaje: no revela si el email existe.
    private fun Throwable.toLoginError() = when (this) {
        AuthFailure.InvalidCredentials, AuthFailure.InvalidEmail, AuthFailure.UserNotFound ->
            LoginError.INVALID_CREDENTIALS
        AuthFailure.TooManyRequests -> LoginError.TOO_MANY_REQUESTS
        AuthFailure.Network -> LoginError.NETWORK
        else -> LoginError.UNKNOWN
    }

    private fun reduce(mutation: LoginMutation) = setState { LoginReducer.reduce(this, mutation) }
}
