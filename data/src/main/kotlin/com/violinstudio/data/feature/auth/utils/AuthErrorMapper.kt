package com.violinstudio.data.feature.auth.utils

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthException
import com.violinstudio.domain.feature.auth.failure.AuthFailure
import java.io.IOException

/** Contexto de la operación: el mismo error de Firebase se traduce distinto según lo que no debe revelar. */
enum class AuthOperation { SIGN_IN, SIGN_UP, GOOGLE_SIGN_IN, PASSWORD_RESET, OTHER }

/**
 * Traduce errores del SDK a [AuthFailure]. Anti-enumeración: al iniciar sesión, contraseña errónea, usuario
 * inexistente o deshabilitado y credencial inválida dan el mismo [AuthFailure.InvalidCredentials]. Solo
 * `PASSWORD_RESET` distingue `UserNotFound`, y es el caso de uso quien lo oculta tras una respuesta uniforme.
 */
object AuthErrorMapper {
    fun map(error: Throwable, operation: AuthOperation = AuthOperation.OTHER): AuthFailure = when (error) {
        is FirebaseNetworkException, is IOException -> AuthFailure.Network
        is FirebaseTooManyRequestsException -> AuthFailure.TooManyRequests
        is FirebaseAuthException -> fromCode(error.errorCode, operation) ?: AuthFailure.Unknown(error, error.errorCode)
        else -> AuthFailure.Unknown(error)
    }

    private fun fromCode(code: String, operation: AuthOperation): AuthFailure? = when (code) {
        "ERROR_USER_NOT_FOUND" ->
            if (operation == AuthOperation.PASSWORD_RESET) AuthFailure.UserNotFound else AuthFailure.InvalidCredentials
        "ERROR_WRONG_PASSWORD", "ERROR_USER_DISABLED" -> AuthFailure.InvalidCredentials
        "ERROR_INVALID_CREDENTIAL" ->
            if (operation == AuthOperation.GOOGLE_SIGN_IN) {
                AuthFailure.ProviderUnavailable
            } else {
                AuthFailure.InvalidCredentials
            }
        "ERROR_INVALID_EMAIL" -> when (operation) {
            AuthOperation.SIGN_UP, AuthOperation.PASSWORD_RESET -> AuthFailure.InvalidEmail
            else -> AuthFailure.InvalidCredentials
        }
        // Vector de enumeración de emails aceptado a propósito: la spec (tasks 4b.1/4a.8) exige un mensaje específico.
        "ERROR_EMAIL_ALREADY_IN_USE" -> AuthFailure.EmailAlreadyInUse
        "ERROR_WEAK_PASSWORD" -> AuthFailure.WeakPassword
        "ERROR_TOO_MANY_REQUESTS" -> AuthFailure.TooManyRequests
        "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL", "ERROR_CREDENTIAL_ALREADY_IN_USE" ->
            AuthFailure.AccountExistsWithOtherProvider
        "ERROR_REQUIRES_RECENT_LOGIN" -> AuthFailure.RequiresRecentLogin
        "ERROR_NETWORK_REQUEST_FAILED" -> AuthFailure.Network
        else -> null
    }
}
