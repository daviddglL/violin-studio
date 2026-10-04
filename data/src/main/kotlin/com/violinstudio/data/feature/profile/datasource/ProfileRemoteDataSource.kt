package com.violinstudio.data.feature.profile.datasource

import kotlinx.coroutines.flow.Flow

/**
 * Instantánea de `users/{uid}`. [isFromCache] distingue "no existe en el servidor" de "no hay nada en la caché
 * local" (sin red): solo el repositorio decide qué hacer con cada caso.
 */
data class ProfileSnapshot(val data: Map<String, Any?>?, val exists: Boolean, val isFromCache: Boolean)

/** Frontera con Firestore (`users/{uid}`); sin lógica, lanza las excepciones crudas del SDK. */
interface ProfileRemoteDataSource {
    /** El flujo falla si el listener falla. */
    fun observe(uid: String): Flow<ProfileSnapshot>

    suspend fun update(uid: String, fields: Map<String, Any>)
}
