package com.violinstudio.data.feature.profile.datasource

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow

class FakeProfileRemoteDataSource : ProfileRemoteDataSource {
    val snapshots = MutableSharedFlow<ProfileSnapshot>(replay = 1)
    var observeFailure: Exception? = null
    var updateFailure: Exception? = null
    var updateHangs = false
    val updates = mutableListOf<Pair<String, Map<String, Any>>>()

    override fun observe(uid: String): Flow<ProfileSnapshot> = flow {
        observeFailure?.let { throw it }
        snapshots.collect { emit(it) }
    }

    override suspend fun update(uid: String, fields: Map<String, Any>) {
        updates += uid to fields
        if (updateHangs) awaitCancellation()
        updateFailure?.let { throw it }
    }
}

/** Fake de los callables: `failure` hace que cualquier llamada lance; `calls` registra nombre y argumento. */
class FakeIdentityFunctionsDataSource : IdentityFunctionsDataSource {
    var failure: Exception? = null
    var response: Any? = null
    val calls = mutableListOf<Pair<String, Any?>>()

    private fun call(name: String, arg: Any? = null): Any? {
        calls += name to arg
        failure?.let { throw it }
        return response
    }

    override suspend fun registerProfile(payload: Map<String, Any?>) = call("registerProfile", payload)

    override suspend fun identityConfig() = call("identityConfig")

    override suspend fun recordConsent(policyVersion: Int) = call("recordConsent", policyVersion)

    override suspend fun revokeConsent() = call("revokeConsent")

    override suspend fun requestGuardianConsent(guardianEmail: String) = call("requestGuardianConsent", guardianEmail)

    /** Si no es nulo, `deleteAccount` espera a que se complete (simula la respuesta lenta del servidor). */
    var deleteGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun deleteAccount() {
        call("deleteAccount")
        deleteGate?.await()
    }
}

suspend fun FakeProfileRemoteDataSource.emitDoc(data: Map<String, Any?>, fromCache: Boolean = false) =
    snapshots.emit(ProfileSnapshot(data, exists = true, isFromCache = fromCache))

suspend fun FakeProfileRemoteDataSource.emitMissing(fromCache: Boolean) =
    snapshots.emit(ProfileSnapshot(null, exists = false, isFromCache = fromCache))
