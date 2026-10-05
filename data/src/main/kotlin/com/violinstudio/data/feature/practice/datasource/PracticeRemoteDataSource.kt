package com.violinstudio.data.feature.practice.datasource

import kotlinx.coroutines.flow.Flow

/** Doc crudo de `practiceSessions`; [hasPendingWrites] viene de los metadatos del snapshot. */
data class PracticeDoc(val id: String, val data: Map<String, Any?>, val hasPendingWrites: Boolean)

/**
 * Frontera con Firestore (`users/{uid}/practiceSessions`). Las escrituras NO esperan al servidor (offline-first):
 * lanzan solo si el SDK rechaza la operación en local.
 */
interface PracticeRemoteDataSource {
    /** Más recientes primero; el flujo falla si el listener falla. */
    fun observe(uid: String, limit: Int): Flow<List<PracticeDoc>>

    /** Lee de la caché local (donde se ven las escrituras en cola); `false` si no está. */
    suspend fun exists(uid: String, id: String): Boolean

    fun create(uid: String, id: String, fields: Map<String, Any>)

    fun update(uid: String, id: String, fields: Map<String, Any>)

    fun delete(uid: String, id: String)
}
