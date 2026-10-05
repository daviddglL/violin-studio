package com.violinstudio.domain.feature.practice.failure

sealed class PracticeFailure(message: String) : Exception(message) {
    /** Fallos de validacion sin traza de pila: son valores, no errores de programa. */
    override fun fillInStackTrace(): Throwable = this

    data object AlreadyRunning : PracticeFailure("Ya hay una sesión en curso")
    data object NotRunning : PracticeFailure("No hay sesión en curso")

    /** Menos de 1 s: no se guarda y la sesión en curso se descarta. */
    data object TooShort : PracticeFailure("Sesión demasiado corta")
    data object NotesTooLong : PracticeFailure("Notas demasiado largas (máx. 500)")
    data object InvalidDuration : PracticeFailure("Duración fuera de rango (1..43200 s)")
    data object InvalidStart : PracticeFailure("Inicio en el futuro")
    data object InvalidId : PracticeFailure("Id de sesión inválido (1..64)")
    data object NoSession : PracticeFailure("Sin sesión")

    /** Las reglas del servidor rechazaron la operación (por ejemplo, sin consentimiento vigente). */
    data object PermissionDenied : PracticeFailure("Operación no permitida")
    data object Unknown : PracticeFailure("Error desconocido")
}
