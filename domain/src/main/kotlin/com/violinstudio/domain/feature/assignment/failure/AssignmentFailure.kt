package com.violinstudio.domain.feature.assignment.failure

sealed class AssignmentFailure(message: String) : Exception(message) {
    /** Fallos como valores, sin traza de pila. */
    override fun fillInStackTrace(): Throwable = this

    data object TitleEmpty : AssignmentFailure("Título vacío")
    data object TitleTooLong : AssignmentFailure("Título demasiado largo (máx. 120)")

    /** Salto de línea, control, formato u otro carácter fuera de la lista blanca de las reglas. */
    data object TitleInvalid : AssignmentFailure("Título con caracteres no permitidos")
    data object DescriptionTooLong : AssignmentFailure("Descripción demasiado larga (máx. 1000)")
    data object InvalidId : AssignmentFailure("Id de tarea inválido (1..64)")
    data object NoSession : AssignmentFailure("Sin sesión")

    /** Las reglas denegaron la operación (vínculo caído, consentimiento revocado). */
    data object PermissionDenied : AssignmentFailure("Operación no permitida")
    data object Unknown : AssignmentFailure("Error desconocido")
}
