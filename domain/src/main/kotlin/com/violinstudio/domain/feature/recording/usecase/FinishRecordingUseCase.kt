package com.violinstudio.domain.feature.recording.usecase

import com.violinstudio.domain.feature.recording.model.QueuedRecording
import com.violinstudio.domain.feature.recording.model.RecordedTake
import com.violinstudio.domain.feature.recording.repository.Recorder
import com.violinstudio.domain.feature.recording.repository.RecordingQueue
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Encola una toma terminada (REQ-REC-04). La toma ya es del receptor (ver `RecordingHandle`): si encolar falla el
 * fichero NO se borra y el fallo se devuelve para que la UI reintente o descarte con `Recorder.deleteTake`.
 */
class FinishRecordingUseCase @Inject constructor(private val queue: RecordingQueue) {
    suspend operator fun invoke(
        take: RecordedTake,
        assignmentId: String? = null,
        sharedWith: String? = null
    ): Result<Unit> = queue.enqueue(QueuedRecording(take, assignmentId = assignmentId, sharedWith = sharedWith))
}

/**
 * Revocacion de `recording_sharing` (REQ-REC-09): vacia la cola del uid y borra sus ficheros locales. Si la cola no se
 * puede vaciar no toca ningun fichero (el reintento posterior sigue viendo los elementos). NO se invoca al cerrar
 * sesion: la cola de un uid se conserva (supuesto aceptado).
 */
class ClearRecordingQueueUseCase @Inject constructor(
    private val queue: RecordingQueue,
    private val recorder: Recorder
) {
    suspend operator fun invoke(uid: String): Result<Unit> = queue.clear(uid).map { removed ->
        // Un fichero que no se borra no detiene al resto (la cola ya esta vacia); la cancelacion si se propaga.
        removed.forEach { item ->
            try {
                recorder.deleteTake(item.take)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Queda huerfano para el barrido de arranque.
            }
        }
    }
}
