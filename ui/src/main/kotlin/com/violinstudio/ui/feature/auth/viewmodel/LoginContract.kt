package com.violinstudio.ui.feature.auth.viewmodel

import com.violinstudio.ui.commons.mvi.UiEffect
import com.violinstudio.ui.commons.mvi.UiIntent
import com.violinstudio.ui.commons.mvi.UiState

enum class LoginFieldError { EMAIL_EMPTY, PASSWORD_EMPTY }

/** Un email inexistente y una contraseña errónea producen el mismo [INVALID_CREDENTIALS]. */
enum class LoginError { INVALID_CREDENTIALS, TOO_MANY_REQUESTS, NETWORK, UNKNOWN }

data class LoginState(
    val email: String = "",
    val password: String = "",
    val emailError: LoginFieldError? = null,
    val passwordError: LoginFieldError? = null,
    val isLoading: Boolean = false,
    val error: LoginError? = null
) : UiState {
    /** La contraseña y el email no salen en logs ni en mensajes de fallo de tests. */
    override fun toString(): String =
        "LoginState(isLoading=$isLoading, emailError=$emailError, passwordError=$passwordError, error=$error)"
}

sealed interface LoginIntent : UiIntent {
    data class EmailChanged(val value: String) : LoginIntent
    data class PasswordChanged(val value: String) : LoginIntent
    data object Submit : LoginIntent
}

/** Sin efectos: no navega a rutas de negocio; el cambio de sesión lo hace el host. */
sealed interface LoginEffect : UiEffect

sealed interface LoginMutation {
    data class EmailChanged(val value: String) : LoginMutation
    data class PasswordChanged(val value: String) : LoginMutation

    /** Valida los campos: con errores los marca; si están bien, pasa a `isLoading`. */
    data object SubmitRequested : LoginMutation
    data object Succeeded : LoginMutation
    data class Failed(val error: LoginError) : LoginMutation
}
