package com.violinstudio.domain.feature.profile.failure

/** Campo de formulario/servidor al que se refiere un [ProfileFailure.InvalidInput]. */
enum class ProfileField(val wire: String) {
    DISPLAY_NAME("displayName"),
    INSTRUMENT("instrument"),
    LOCALE("locale"),
    BIRTH_DATE("birthDate");

    companion object {
        fun fromWire(value: String?): ProfileField? = entries.firstOrNull { it.wire == value }
    }
}

sealed class ProfileFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Menor de edad con el flujo de tutor desactivado (`UNDERAGE_NOT_ALLOWED`). */
    data object UnderageNotAllowed : ProfileFailure("Menor no permitido")
    data object InvalidBirthDate : ProfileFailure("Fecha de nacimiento inválida")

    /** Dato inválido (`INVALID_ARGUMENT`, o validación local); [field] es `null` si no se conoce. */
    class InvalidInput(val field: ProfileField?) : ProfileFailure("Datos de perfil inválidos")
    data object NoProfile : ProfileFailure("No existe perfil")
    data object EmailNotVerified : ProfileFailure("Email sin verificar")

    /** Las reglas del servidor rechazaron la escritura: consentimiento no concedido, borrado en curso o claim obsoleto. */
    data object NotAllowed : ProfileFailure("Operación no permitida")
    data object Network : ProfileFailure("Sin conexión")

    /** La causa puede contener PII: no se registra en logs ni se muestra al usuario. */
    class Unknown(cause: Throwable? = null) : ProfileFailure("Error de perfil desconocido", cause)
}
