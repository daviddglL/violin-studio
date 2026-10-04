package com.violinstudio.ui.feature.auth.viewmodel

import com.violinstudio.domain.feature.auth.model.GoogleIdToken
import com.violinstudio.ui.commons.mvi.UiEffect
import com.violinstudio.ui.commons.mvi.UiIntent
import com.violinstudio.ui.commons.mvi.UiState

enum class LoginFieldError { EMAIL_EMPTY, PASSWORD_EMPTY }

/** Un email inexistente y una contraseña errónea producen el mismo [INVALID_CREDENTIALS]. */
enum class LoginError {
    INVALID_CREDENTIALS,
    TOO_MANY_REQUESTS,
    NETWORK,

    /** Google no está disponible (sin cuenta, sin Play Services, sin configuración...): el formulario sigue operativo. */
    GOOGLE_UNAVAILABLE,

    /** El email ya existe con otro proveedor: se guía al usuario, sin vincular cuentas. */
    ACCOUNT_EXISTS_OTHER_PROVIDER
}

data class LoginState(
    val email: String = "",
    val password: String = "",
    val emailError: LoginFieldError? = null,
    val passwordError: LoginFieldError? = null,
    val isLoading: Boolean = false,
    val error: LoginError? = null,
    val succeeded: Boolean = false
) : UiState {
    /** Tras un acceso correcto el envío sigue bloqueado hasta que la sesión sustituya la pantalla. */
    val canSubmit: Boolean get() = !isLoading && !succeeded

    /** La contraseña y el email no salen en logs ni en mensajes de fallo de tests. */
    override fun toString(): String =
        "LoginState(isLoading=$isLoading, emailError=$emailError, passwordError=$passwordError, error=$error)"
}

sealed interface LoginIntent : UiIntent {
    data class EmailChanged(val value: String) : LoginIntent
    data class PasswordChanged(val value: String) : LoginIntent
    data object Submit : LoginIntent

    /** Credential Manager entregó un ID token de Google. Cancelar la hoja no genera intent: no hay nada que cambiar. */
    data class GoogleTokenReceived(val token: GoogleIdToken) : LoginIntent

    /** Google no está disponible (el solicitante falló o no hay proveedor). */
    data object GoogleFailed : LoginIntent

    /** Se sale de la pantalla: se olvidan contraseña, mensajes y errores; el email escrito se conserva. */
    data object ScreenLeft : LoginIntent
}

/** Sin efectos: no navega a rutas de negocio; el cambio de sesión lo hace el host. */
sealed interface LoginEffect : UiEffect

sealed interface LoginMutation {
    data class EmailChanged(val value: String) : LoginMutation
    data class PasswordChanged(val value: String) : LoginMutation

    /** Valida los campos: con errores los marca; si están bien, pasa a `isLoading`. */
    data object SubmitRequested : LoginMutation
    data object Succeeded : LoginMutation
    data object ScreenLeft : LoginMutation

    /** Empieza el acceso con Google: bloquea el envío mientras dura. */
    data object GoogleStarted : LoginMutation

    /** El usuario canceló: no es un error y el estado queda como estaba. */
    data object GoogleCancelled : LoginMutation
    data class Failed(val error: LoginError) : LoginMutation
}
