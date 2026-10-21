package com.violinstudio.domain.feature.link.failure

/**
 * Mapeo para A7b (`details.reason` del callable -> fallo; el código HTTPS entre paréntesis):
 *
 * | reason                                              | LinkFailure                            |
 * |-----------------------------------------------------|----------------------------------------|
 * | `INVALID_ARGUMENT` (invalid-argument), `CODE_NOT_FOUND` (not-found) | [InvalidCode]            |
 * | `RATE_LIMITED` (resource-exhausted, `retryAfterSeconds`) | [RateLimited]                     |
 * | `TEACHER_FULL` (resource-exhausted)                 | [TeacherFull]                          |
 * | `CODE_LIMIT_REACHED` (resource-exhausted)           | [CodeLimitReached]                     |
 * | `ALREADY_LINKED` (failed-precondition)              | [AlreadyLinked]                        |
 * | `NOTICE_OUTDATED` (failed-precondition)             | [NoticeOutdated]                       |
 * | `NO_ACTIVE_CONSENT`, `NO_PROFILE` (failed-precondition) | [NoActiveConsent]                  |
 * | `NOT_ELIGIBLE` (permission-denied)                  | [NotEligible]                          |
 * | `NOT_TEACHER` (permission-denied)                   | [NotTeacher]                           |
 * | `NOT_LINK_PARTY` (permission-denied)                | [PermissionDenied]                     |
 * | `TEMPORARILY_UNAVAILABLE` (unavailable)             | [TemporarilyUnavailable]               |
 * | sin red (`unavailable` sin reason, `deadline-exceeded`) | [Offline]                          |
 * | cualquier otro                                      | [Unknown]                              |
 */
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

    /** El perfil no existe o no tiene consentimiento vigente (`NO_ACTIVE_CONSENT`/`NO_PROFILE`). */
    data object NoActiveConsent : LinkFailure("Sin consentimiento vigente")

    /** Fallo transitorio del servidor (`TEMPORARILY_UNAVAILABLE`): reintentable, distinto de no tener red. */
    data object TemporarilyUnavailable : LinkFailure("Servicio no disponible temporalmente")

    /** Límite de códigos activos o de creación en 24 h (`CODE_LIMIT_REACHED`). */
    data object CodeLimitReached : LinkFailure("Límite de códigos alcanzado")
    data object NotTeacher : LinkFailure("Solo para profesores")
    data object NoSession : LinkFailure("Sin sesión")

    /** Las reglas denegaron la lectura (vínculo caído, consentimiento revocado) o quien llama no es parte del vínculo. */
    data object PermissionDenied : LinkFailure("Operación no permitida")
    data object Offline : LinkFailure("Sin conexión")
    data object Unknown : LinkFailure("Error desconocido")
}
