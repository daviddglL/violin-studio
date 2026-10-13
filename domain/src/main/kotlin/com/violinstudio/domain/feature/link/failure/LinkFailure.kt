package com.violinstudio.domain.feature.link.failure

sealed class LinkFailure(message: String) : Exception(message) {
    /** Fallos como valores, sin traza de pila. */
    override fun fillInStackTrace(): Throwable = this

    /** Malformado en cliente o `CODE_NOT_FOUND` del servidor (inexistente, caducado, usado o revocado: indistinguibles). */
    data object InvalidCode : LinkFailure("Código inválido")
    data class RateLimited(val retryAfterSeconds: Long?) : LinkFailure("Demasiados intentos")
    data object AlreadyLinked : LinkFailure("Ya vinculado")
    data object TeacherFull : LinkFailure("El profesor no admite más alumnos")
    data object NotEligible : LinkFailure("Cuenta no elegible")
    data object NoticeOutdated : LinkFailure("Aviso de vínculo desactualizado")

    /** Límite de códigos activos o de creación en 24 h (`CODE_LIMIT_REACHED`). */
    data object CodeLimitReached : LinkFailure("Límite de códigos alcanzado")
    data object NotTeacher : LinkFailure("Solo para profesores")
    data object NoSession : LinkFailure("Sin sesión")

    /** Las reglas denegaron la lectura (vínculo caído, consentimiento revocado). */
    data object PermissionDenied : LinkFailure("Operación no permitida")
    data object Offline : LinkFailure("Sin conexión")
    data object Unknown : LinkFailure("Error desconocido")
}
