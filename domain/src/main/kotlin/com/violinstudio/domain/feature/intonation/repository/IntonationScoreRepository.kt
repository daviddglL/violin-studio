package com.violinstudio.domain.feature.intonation.repository

import kotlinx.coroutines.flow.Flow

/**
 * Mejor puntuacion (0..100) por ejercicio, por usuario y solo en el dispositivo (se borra con los datos locales del
 * usuario). Leer nunca falla: un error es "sin puntuaciones". Escribir falla con `IntonationFailure.Storage`.
 */
interface IntonationScoreRepository {
    fun observeBest(uid: String): Flow<Map<String, Int>>

    /** Guarda [score] si supera la mejor de [exerciseId]. */
    suspend fun saveIfBest(uid: String, exerciseId: String, score: Int)
}
