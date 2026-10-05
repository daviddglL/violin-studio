package com.violinstudio.domain.feature.practice.repository

import com.violinstudio.domain.feature.practice.model.PracticeDraft
import com.violinstudio.domain.feature.practice.model.PracticeRules
import com.violinstudio.domain.feature.practice.model.PracticeSession
import kotlinx.coroutines.flow.Flow

/**
 * Historial en `users/{uid}/practiceSessions`. Escribe offline-first (sin esperar al servidor): los fallos
 * llegan como `Result.failure(PracticeFailure)`; un rechazo del servidor posterior no se notifica.
 */
interface PracticeLogRepository {
    /** Más recientes primero, como mucho [limit]. */
    fun observeHistory(uid: String, limit: Int = PracticeRules.HISTORY_LIMIT): Flow<List<PracticeSession>>

    suspend fun create(uid: String, draft: PracticeDraft): Result<Unit>

    /**
     * Si ya existe el doc [id], leído de la caché local (donde se ven las escrituras en cola). Límite conocido: si la
     * caché no lo tiene (persistencia desactivada, expulsión o purga) devuelve `false`; entonces un `create` sobre un
     * doc que sí existe en el servidor se evalúa como update, las reglas lo deniegan y el servidor conserva el doc
     * original: las notas nuevas se pierden en silencio.
     */
    suspend fun exists(uid: String, id: String): Boolean

    /** `null` borra las notas. Solo las notas son editables. */
    suspend fun updateNotes(uid: String, id: String, notes: String?): Result<Unit>

    suspend fun delete(uid: String, id: String): Result<Unit>
}
