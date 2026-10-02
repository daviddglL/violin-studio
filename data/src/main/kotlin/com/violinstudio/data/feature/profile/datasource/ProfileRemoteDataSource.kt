package com.violinstudio.data.feature.profile.datasource

import kotlinx.coroutines.flow.Flow

/** Frontera con Firestore (`users/{uid}`); sin lógica, lanza las excepciones crudas del SDK. */
interface ProfileRemoteDataSource {
    /** Datos crudos del documento; `null` mientras no exista. El flujo falla si el listener falla. */
    fun observe(uid: String): Flow<Map<String, Any?>?>

    suspend fun update(uid: String, fields: Map<String, Any>)
}
