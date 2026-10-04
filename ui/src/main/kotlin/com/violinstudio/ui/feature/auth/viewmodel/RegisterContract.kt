package com.violinstudio.ui.feature.auth.viewmodel

import com.violinstudio.domain.feature.auth.model.GoogleIdToken
import com.violinstudio.ui.commons.mvi.UiEffect
import com.violinstudio.ui.commons.mvi.UiIntent
import com.violinstudio.ui.commons.mvi.UiState

/** Longitud mínima de contraseña de Firebase: solo feedback local, el servidor decide (`WeakPassword`). */
const val MIN_PASSWORD_LENGTH = 6

enum class RegisterFieldError { EMAIL_EMPTY, EMAIL_INVALID, PASSWORD_EMPTY, PASSWORD_TOO_SHORT, PASSWORD_WEAK }

/** [ACCOUNT_UNAVAILABLE] es genérico a propósito: no añade datos sobre la cuenta existente. */
enum class RegisterError {
    ACCOUNT_UNAVAILABLE,
    TOO_MANY_REQUESTS,
    NETWORK,
    UNKNOWN,
    GOOGLE_UNAVAILABLE,
    ACCOUNT_EXISTS_OTHER_PROVIDER
}

data class RegisterState(
    val email: String = "",
    val password: String = "",
    val emailError: RegisterFieldError? = null,
    val passwordError: RegisterFieldError? = null,
    val isLoading: Boolean = false,
    val error: RegisterError? = null,
    val succeeded: Boolean = false
) : UiState {
    /** Tras un acceso correcto el envío sigue bloqueado hasta que la sesión sustituya la pantalla. */
    val canSubmit: Boolean get() = !isLoading && !succeeded

    override fun toString(): String =
        "RegisterState(isLoading=$isLoading, emailError=$emailError, passwordError=$passwordError, error=$error)"
}

sealed interface RegisterIntent : UiIntent {
    data class EmailChanged(val value: String) : RegisterIntent
    data class PasswordChanged(val value: String) : RegisterIntent
    data object Submit : RegisterIntent
    data class GoogleTokenReceived(val token: GoogleIdToken) : RegisterIntent
    data object GoogleFailed : RegisterIntent
    data object ScreenLeft : RegisterIntent
}

/** Sin efectos: tras el registro `authUser` emite un usuario sin verificar y la sesión lleva a verificación. */
sealed interface RegisterEffect : UiEffect

sealed interface RegisterMutation {
    data class EmailChanged(val value: String) : RegisterMutation
    data class PasswordChanged(val value: String) : RegisterMutation
    data object SubmitRequested : RegisterMutation
    data object Succeeded : RegisterMutation
    data object ScreenLeft : RegisterMutation
    data object GoogleStarted : RegisterMutation
    data object GoogleCancelled : RegisterMutation

    /** El servidor rechazó un campo concreto (`WeakPassword`, `InvalidEmail`). */
    data class Rejected(val emailError: RegisterFieldError?, val passwordError: RegisterFieldError?) :
        RegisterMutation

    data class Failed(val error: RegisterError) : RegisterMutation
}
