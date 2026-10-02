package com.violinstudio.ui.feature.auth.viewmodel

import com.violinstudio.domain.feature.auth.failure.AuthFailure
import com.violinstudio.domain.feature.auth.model.GoogleIdToken
import com.violinstudio.domain.feature.auth.usecase.SignInWithEmailUseCase
import com.violinstudio.domain.feature.auth.usecase.SignInWithGoogleUseCase
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
    private val signInWithEmail: SignInWithEmailUseCase,
    private val signInWithGoogle: SignInWithGoogleUseCase
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
        LoginIntent.ScreenLeft -> reduce(LoginMutation.ScreenLeft)
        is LoginIntent.GoogleTokenReceived -> onGoogleToken(intent.token)
        LoginIntent.GoogleFailed -> reduce(LoginMutation.Failed(LoginError.GOOGLE_UNAVAILABLE))
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

    // Cancelar la hoja de Google no es un error (el caso de uso devuelve `null`): el estado queda como estaba.
    private suspend fun onGoogleToken(token: GoogleIdToken) {
        reduce(LoginMutation.GoogleStarted)
        val result = try {
            signInWithGoogle(token)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            Result.failure(AuthFailure.Unknown())
        }
        result.fold(
            onSuccess = { reduce(if (it == null) LoginMutation.GoogleCancelled else LoginMutation.Succeeded) },
            onFailure = { reduce(LoginMutation.Failed(it.toGoogleError())) }
        )
    }

    // Con Google el email ya está verificado y el usuario se identificó: el conflicto de proveedor se explica.
    private fun Throwable.toGoogleError() = when (this) {
        AuthFailure.AccountExistsWithOtherProvider -> LoginError.ACCOUNT_EXISTS_OTHER_PROVIDER
        AuthFailure.TooManyRequests -> LoginError.TOO_MANY_REQUESTS
        AuthFailure.Network -> LoginError.NETWORK
        else -> LoginError.GOOGLE_UNAVAILABLE
    }

    // Defensa en profundidad: todo fallo salvo red y límite de intentos (cuenta inexistente, deshabilitada, otro
    // proveedor, desconocido...) comparte el mensaje de credenciales erróneas; la UI no depende de lo que mapee datos.
    private fun Throwable.toLoginError() = when (this) {
        AuthFailure.TooManyRequests -> LoginError.TOO_MANY_REQUESTS
        AuthFailure.Network -> LoginError.NETWORK
        else -> LoginError.INVALID_CREDENTIALS
    }

    private fun reduce(mutation: LoginMutation) = setState { LoginReducer.reduce(this, mutation) }
}
