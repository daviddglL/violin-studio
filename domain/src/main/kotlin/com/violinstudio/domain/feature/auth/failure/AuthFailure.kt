package com.violinstudio.domain.feature.auth.failure

/**
 * Fallos de autenticación. Son excepciones para viajar dentro de [Result.failure].
 * `InvalidCredentials` cubre usuario inexistente y contraseña errónea: no revela si el email existe.
 */
sealed class AuthFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    data object InvalidCredentials : AuthFailure("Credenciales inválidas")
    data object EmailAlreadyInUse : AuthFailure("El email ya está en uso")
    data object WeakPassword : AuthFailure("Contraseña débil")
    data object AccountExistsWithOtherProvider : AuthFailure("La cuenta existe con otro proveedor")
    data object TooManyRequests : AuthFailure("Demasiados intentos")
    data object Network : AuthFailure("Sin conexión")

    /** No existe cuenta con ese email; solo lo produce `sendPasswordReset`, cuya respuesta pública es uniforme. */
    data object UserNotFound : AuthFailure("Usuario no encontrado")
    data object InvalidEmail : AuthFailure("Formato de email inválido")
    data object Cancelled : AuthFailure("Operación cancelada")
    data object ProviderUnavailable : AuthFailure("Proveedor de identidad no disponible")
    data object RequiresRecentLogin : AuthFailure("Requiere autenticación reciente")

    /** La causa puede contener PII (p. ej. el email): no se registra en logs ni se muestra al usuario. */
    class Unknown(cause: Throwable? = null) : AuthFailure("Error de autenticación desconocido", cause)
}
