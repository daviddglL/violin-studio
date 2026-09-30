package com.violinstudio.data.feature.health.datasource.firebase

import com.google.firebase.functions.FirebaseFunctions
import com.violinstudio.data.feature.health.datasource.HealthRemoteDataSource
import com.violinstudio.data.feature.health.dto.HealthDto
import com.violinstudio.data.feature.health.utils.HealthResponseParser
import javax.inject.Inject
import kotlinx.coroutines.tasks.await

/** Adaptador fino sobre el SDK; se prueba en el E2E (Task 11). */
class FirebaseHealthRemoteDataSource @Inject constructor(
    private val functions: FirebaseFunctions
) : HealthRemoteDataSource {
    override suspend fun fetchHealth(): HealthDto =
        HealthResponseParser.parse(functions.getHttpsCallable("health").call().await().getData())
}
