package com.violinstudio.domain.feature.account.failure

sealed class AccountFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** El servidor exige reautenticación reciente (`REAUTH_REQUIRED`): la UI debe reautenticar y reintentar. */
    data object RequiresRecentLogin : AccountFailure("Requiere autenticación reciente")
    data object ErasureFailed : AccountFailure("No se pudo completar el borrado")
    /** `unauthenticated` del callable con la cuenta aún existente (token/App Check rechazado): NO es borrado. */
    data object Unauthenticated : AccountFailure("Sesión o verificación de la app rechazada")
    data object Network : AccountFailure("Sin conexión")
    class Unknown(cause: Throwable? = null) : AccountFailure("Error de cuenta desconocido", cause)
}
