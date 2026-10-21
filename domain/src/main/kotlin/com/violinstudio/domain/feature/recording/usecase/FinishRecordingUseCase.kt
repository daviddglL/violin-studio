package com.violinstudio.domain.feature.recording.usecase

import com.violinstudio.domain.feature.recording.model.QueueState
import com.violinstudio.domain.feature.recording.model.QueuedRecording
import com.violinstudio.domain.feature.recording.model.RecordedTake
import com.violinstudio.domain.feature.recording.repository.Recorder
import com.violinstudio.domain.feature.recording.repository.RecordingQueue
import com.violinstudio.domain.feature.recording.repository.RecordingUploadScheduler
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Encola una toma terminada (REQ-REC-04). La toma ya es del receptor (ver `RecordingHandle`): si encolar falla el
 * fichero NO se borra y el fallo se devuelve para que la UI reintente o descarte con `Recorder.deleteTake`.
 */
class FinishRecordingUseCase @Inject constructor(
    private val queue: RecordingQueue,
    private val scheduler: RecordingUploadScheduler
) {
    suspend operator fun invoke(
        take: RecordedTake,
        assignmentId: String? = null,
        sharedWith: String? = null
    ): Result<Unit> = queue.enqueue(QueuedRecording(take, assignmentId = assignmentId, sharedWith = sharedWith))
        .onSuccess {
            // La cola es la fuente de verdad: si WorkManager falla, el arranque reprograma lo pendiente (B11).
            runCatching { scheduler.schedule(take.uid, take.id) }
        }
}

/**
 * Descarta una toma de la cola (R3). Sin subida en marcha (`recorded`/`failed`) borra fichero y elemento; con la subida
 * en curso o terminada la marca `cancelled`, detiene el trabajo y lo reprograma para que el worker limpie el fichero y,
 * si el objeto ya llego a Storage, el doc que `finalizeRecording` cree despues.
 */
class DiscardRecordingUseCase @Inject constructor(
    private val queue: RecordingQueue,
    private val scheduler: RecordingUploadScheduler,
    private val recorder: Recorder
) {
    suspend operator fun invoke(uid: String, id: String): Result<Unit> =
        queue.update(uid, id) { it.copy(cancelled = true) }.mapCatching { marked ->
            if (marked != null) {
                runCatching { scheduler.cancel(id) }
                when (marked.state) {
                    QueueState.RECORDED, QueueState.FAILED, QueueState.DONE -> {
                        recorder.deleteTake(marked.take)
                        queue.remove(uid, id).getOrThrow()
                    }
                    QueueState.UPLOADING, QueueState.UPLOADED -> runCatching { scheduler.schedule(uid, id) }
                }
            }
        }
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
