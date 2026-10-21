package com.violinstudio.domain.feature.consent.failure

sealed class ConsentFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    data class PolicyOutdated(val currentVersion: Int?) : ConsentFailure("Versión de política desactualizada")
    data object GuardianRequired : ConsentFailure("Un menor necesita consentimiento de tutor")
    data class RateLimited(val retryAfterSeconds: Long?) : ConsentFailure("Demasiadas solicitudes")
    data object GuardianEmailInvalid : ConsentFailure("Email de tutor inválido")

    /** Permiso de grabación: el email no es el del tutor que aceptó la política. */
    data object GuardianEmailMismatch : ConsentFailure("No es el tutor que aceptó la política")

    /** Permiso de grabación: el consentimiento base es antiguo y no guarda qué tutor lo dio. */
    data object GuardianLegacyConsent : ConsentFailure("Consentimiento de tutor sin verificar")
    data object NotMinor : ConsentFailure("La cuenta no es de un menor")
    data object UnderageNotAllowed : ConsentFailure("Menor no permitido")
    data object NoProfile : ConsentFailure("No existe perfil")
    data object NoActiveConsent : ConsentFailure("No hay consentimiento activo")
    data object AlreadyGranted : ConsentFailure("Consentimiento ya concedido")
    data object RecordingDisabled : ConsentFailure("Las grabaciones están desactivadas")
    data class RecordingConsentOutdated(val currentVersion: Int?) :
        ConsentFailure("Consentimiento de grabación desactualizado")

    /** Borrado de una revocación anterior aún en curso: reintentable. */
    data object TemporarilyUnavailable : ConsentFailure("Servicio temporalmente no disponible")
    data object EmailNotVerified : ConsentFailure("Email sin verificar")

    /** `INVALID_ARGUMENT` del servidor; [field] viene de `details.field` (p. ej. `policyVersion`, `guardianEmail`). */
    data class InvalidArgument(val field: String?) : ConsentFailure("Argumento inválido")
    data object Network : ConsentFailure("Sin conexión")
    class Unknown(cause: Throwable? = null) : ConsentFailure("Error de consentimiento desconocido", cause)
}
