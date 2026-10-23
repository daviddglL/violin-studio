package com.violinstudio.data.feature.curriculum.datasource

import kotlinx.coroutines.flow.Flow

/**
 * Frontera con Firestore (`users/{uid}/progress/curriculum`). [add] y [remove] NO esperan al servidor (offline-first):
 * lanzan solo si el SDK rechaza la operacion en local.
 */
interface CurriculumProgressRemoteDataSource {
    /** Ids de las lecciones hechas; vacio si el doc no existe. El flujo falla si el listener falla. */
    fun observe(uid: String): Flow<List<String>>

    fun add(uid: String, ids: Collection<String>)

    /** Como [add], pero vuelve cuando el servidor confirma la escritura (migracion desde el dispositivo). */
    suspend fun addAndAwaitServer(uid: String, ids: Collection<String>)

    fun remove(uid: String, id: String)
}
