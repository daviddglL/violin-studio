package com.violinstudio.data.feature.profile.datasource.firebase

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.violinstudio.data.commons.firebase.FunctionsCallException
import com.violinstudio.data.feature.profile.datasource.IdentityFunctionsDataSource
import javax.inject.Inject
import kotlinx.coroutines.tasks.await

/** Adaptador fino sobre los callables: solo cambia el tipo de la excepción del SDK; se prueba en el E2E (8b). */
class FirebaseIdentityFunctionsDataSource @Inject constructor(private val functions: FirebaseFunctions) :
    IdentityFunctionsDataSource {
    override suspend fun registerProfile(payload: Map<String, Any?>): Any? = call("registerProfile", payload)

    override suspend fun identityConfig(): Any? = call("identityConfig")

    override suspend fun recordConsent(policyVersion: Int): Any? =
        call("recordConsent", mapOf("policyVersion" to policyVersion))

    override suspend fun revokeConsent(): Any? = call("revokeConsent")

    override suspend fun requestGuardianConsent(guardianEmail: String): Any? =
        call("requestGuardianConsent", mapOf("guardianEmail" to guardianEmail))

    override suspend fun deleteAccount(): Any? = call("deleteAccount")

    private suspend fun call(name: String, data: Any? = null): Any? = try {
        functions.getHttpsCallable(name).call(data).await().getData()
    } catch (e: FirebaseFunctionsException) {
        throw FunctionsCallException(e.code.name, e.details, e)
    }
}
