package com.violinstudio.core.data.remote.firebase

import com.google.firebase.functions.FirebaseFunctions
import com.violinstudio.core.data.health.HealthRemoteSource
import com.violinstudio.core.data.remote.HealthResponseParser
import com.violinstudio.core.model.HealthInfo
import javax.inject.Inject
import kotlinx.coroutines.tasks.await

/** Adaptador fino sobre el SDK; se prueba en el E2E (Task 11). */
class FirebaseHealthRemoteSource @Inject constructor(
    private val functions: FirebaseFunctions
) : HealthRemoteSource {
    override suspend fun fetchHealth(): HealthInfo =
        HealthResponseParser.parse(functions.getHttpsCallable("health").call().await().getData())
}
