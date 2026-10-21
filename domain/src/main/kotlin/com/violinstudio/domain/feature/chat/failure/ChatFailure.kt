package com.violinstudio.domain.feature.chat.failure

sealed class ChatFailure(message: String) : Exception(message) {
    /** Fallos como valores, sin traza de pila. */
    override fun fillInStackTrace(): Throwable = this

    data object Empty : ChatFailure("Mensaje vacio")
    data object TooLong : ChatFailure("Mensaje demasiado largo (max. 1000)")
    data object NoSession : ChatFailure("Sin sesion")

    /** Las reglas denegaron la operacion (vinculo caido, consentimiento revocado). */
    data object PermissionDenied : ChatFailure("Operacion no permitida")
    data object Unknown : ChatFailure("Error desconocido")
}
