package com.violinstudio.domain.feature.consent.failure

sealed class ConsentFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class PolicyOutdated(val currentVersion: Int?) : ConsentFailure("Versión de política desactualizada")
    data object GuardianRequired : ConsentFailure("Un menor necesita consentimiento de tutor")
    class RateLimited(val retryAfterSeconds: Long?) : ConsentFailure("Demasiadas solicitudes")
    data object GuardianEmailInvalid : ConsentFailure("Email de tutor inválido")
    data object NotMinor : ConsentFailure("La cuenta no es de un menor")
    data object UnderageNotAllowed : ConsentFailure("Menor no permitido")
    data object NoProfile : ConsentFailure("No existe perfil")
    data object NoActiveConsent : ConsentFailure("No hay consentimiento activo")
    data object AlreadyGranted : ConsentFailure("Consentimiento ya concedido")
    data object EmailNotVerified : ConsentFailure("Email sin verificar")

    /** `INVALID_ARGUMENT` del servidor; [field] viene de `details.field` (p. ej. `policyVersion`, `guardianEmail`). */
    class InvalidArgument(val field: String?) : ConsentFailure("Argumento inválido")
    data object Network : ConsentFailure("Sin conexión")
    class Unknown(cause: Throwable? = null) : ConsentFailure("Error de consentimiento desconocido", cause)
}
