package com.violinstudio.ui.feature.auth.viewmodel

import com.violinstudio.domain.feature.auth.failure.AuthFailure
import com.violinstudio.domain.feature.auth.model.GoogleIdToken
import com.violinstudio.domain.feature.auth.usecase.SignInWithGoogleUseCase
import com.violinstudio.domain.feature.auth.usecase.SignUpWithEmailUseCase
import com.violinstudio.ui.commons.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/**
 * No navega: tras registrarse, `authUser` emite un usuario sin verificar y la sesión lleva a la verificación. Una
 * contraseña débil solo marca el campo. `EmailAlreadyInUse` (enumeración aceptada por la spec) se muestra con un
 * mensaje genérico sin datos de la cuenta.
 */
@HiltViewModel
class RegisterViewModel @Inject constructor(
    private val signUpWithEmail: SignUpWithEmailUseCase,
    private val signInWithGoogle: SignInWithGoogleUseCase
) : MviViewModel<RegisterState, RegisterIntent, RegisterEffect>(RegisterState()) {
    private var submitPending = false

    override fun onIntent(intent: RegisterIntent) {
        if (intent == RegisterIntent.Submit) {
            if (submitPending) return
            submitPending = true
        }
        super.onIntent(intent)
    }

    override suspend fun handleIntent(intent: RegisterIntent) = when (intent) {
        is RegisterIntent.EmailChanged -> reduce(RegisterMutation.EmailChanged(intent.value))
        is RegisterIntent.PasswordChanged -> reduce(RegisterMutation.PasswordChanged(intent.value))
        RegisterIntent.Submit -> onSubmit()
        RegisterIntent.ScreenLeft -> reduce(RegisterMutation.ScreenLeft)
        is RegisterIntent.GoogleTokenReceived -> onGoogleToken(intent.token)
        RegisterIntent.GoogleFailed -> reduce(RegisterMutation.Failed(RegisterError.GOOGLE_UNAVAILABLE))
    }

    private suspend fun onSubmit() {
        try {
            reduce(RegisterMutation.SubmitRequested)
            if (!state.value.isLoading) return
            val result = try {
                signUpWithEmail(state.value.email, state.value.password)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                Result.failure(AuthFailure.Unknown())
            }
            result.fold(
                onSuccess = { reduce(RegisterMutation.Succeeded) },
                onFailure = {
                    logUnknown(it)
                    reduce(it.toMutation())
                }
            )
        } finally {
            submitPending = false
        }
    }

    // Cancelar la hoja de Google no es un error (el caso de uso devuelve `null`): el estado queda como estaba.
    private suspend fun onGoogleToken(token: GoogleIdToken) {
        reduce(RegisterMutation.GoogleStarted)
        val result = try {
            signInWithGoogle(token)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            Result.failure(AuthFailure.Unknown())
        }
        result.fold(
            onSuccess = {
                reduce(if (it == null) RegisterMutation.GoogleCancelled else RegisterMutation.Succeeded)
            },
            onFailure = { reduce(RegisterMutation.Failed(it.toGoogleError())) }
        )
    }

    private fun Throwable.toGoogleError() = when (this) {
        AuthFailure.AccountExistsWithOtherProvider -> RegisterError.ACCOUNT_EXISTS_OTHER_PROVIDER
        AuthFailure.TooManyRequests -> RegisterError.TOO_MANY_REQUESTS
        AuthFailure.Network -> RegisterError.NETWORK
        else -> RegisterError.GOOGLE_UNAVAILABLE
    }

    private fun Throwable.toMutation(): RegisterMutation = when (this) {
        AuthFailure.WeakPassword -> RegisterMutation.Rejected(null, RegisterFieldError.PASSWORD_WEAK)
        AuthFailure.InvalidEmail -> RegisterMutation.Rejected(RegisterFieldError.EMAIL_INVALID, null)
        AuthFailure.EmailAlreadyInUse, AuthFailure.AccountExistsWithOtherProvider ->
            RegisterMutation.Failed(RegisterError.ACCOUNT_UNAVAILABLE)
        AuthFailure.TooManyRequests -> RegisterMutation.Failed(RegisterError.TOO_MANY_REQUESTS)
        AuthFailure.Network -> RegisterMutation.Failed(RegisterError.NETWORK)
        else -> RegisterMutation.Failed(RegisterError.UNKNOWN)
    }

    /** Diagnostico sin PII: solo el mensaje fijo del fallo (con el codigo de Firebase) y las clases de la cadena de causas. */
    private fun logUnknown(failure: Throwable) {
        if (failure is AuthFailure.Unknown) {
            val causes = generateSequence(failure.cause) { it.cause }.joinToString { it.javaClass.name }
            runCatching { android.util.Log.w("ViolinAuth", "sign-up failed: ${failure.message} causes=[$causes]") }
        }
    }

    private fun reduce(mutation: RegisterMutation) = setState { RegisterReducer.reduce(this, mutation) }
}
