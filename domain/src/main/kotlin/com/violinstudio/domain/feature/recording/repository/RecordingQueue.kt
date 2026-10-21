package com.violinstudio.domain.feature.recording.repository

import com.violinstudio.domain.feature.recording.model.QueuedRecording
import kotlinx.coroutines.flow.Flow

/** Cola local de subida por uid (DataStore). La cola de un uid sobrevive al cierre de sesion (supuesto aceptado). */
interface RecordingQueue {
    suspend fun enqueue(item: QueuedRecording): Result<Unit>

    fun observe(uid: String): Flow<List<QueuedRecording>>

    /** Aplica [transform] al elemento `id` si existe; devuelve el resultado o `null` si no estaba. */
    suspend fun update(
        uid: String,
        id: String,
        transform: (QueuedRecording) -> QueuedRecording
    ): Result<QueuedRecording?>

    suspend fun remove(uid: String, id: String): Result<Unit>

    /** Vacia la cola del uid y devuelve lo que habia (para borrar sus ficheros). */
    suspend fun clear(uid: String): Result<List<QueuedRecording>>
}
