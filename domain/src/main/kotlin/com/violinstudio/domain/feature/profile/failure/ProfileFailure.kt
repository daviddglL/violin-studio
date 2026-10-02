package com.violinstudio.domain.feature.profile.failure

sealed class ProfileFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Menor de edad con el flujo de tutor desactivado (`UNDERAGE_NOT_ALLOWED`). */
    data object UnderageNotAllowed : ProfileFailure("Menor no permitido")
    data object InvalidBirthDate : ProfileFailure("Fecha de nacimiento inválida")
    data object InvalidInput : ProfileFailure("Datos de perfil inválidos")
    data object NoProfile : ProfileFailure("No existe perfil")
    data object EmailNotVerified : ProfileFailure("Email sin verificar")
    data object Network : ProfileFailure("Sin conexión")
    class Unknown(cause: Throwable? = null) : ProfileFailure("Error de perfil desconocido", cause)
}
