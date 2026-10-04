package com.violinstudio.data.feature.profile.datasource

/**
 * Frontera con los callables de identidad (región `europe-west1`). Devuelve el `data` crudo de la respuesta y
 * lanza `FunctionsCallException` (ver `FunctionsErrorMapper`) o la excepción de transporte; sin más lógica.
 */
interface IdentityFunctionsDataSource {
    suspend fun registerProfile(payload: Map<String, Any?>): Any?

    suspend fun identityConfig(): Any?

    suspend fun recordConsent(policyVersion: Int): Any?

    suspend fun revokeConsent(): Any?

    suspend fun requestGuardianConsent(guardianEmail: String): Any?

    suspend fun deleteAccount(): Any?
}
