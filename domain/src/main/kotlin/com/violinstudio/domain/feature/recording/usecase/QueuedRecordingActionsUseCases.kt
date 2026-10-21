package com.violinstudio.domain.feature.recording.usecase

import com.violinstudio.domain.feature.recording.model.QueueState
import com.violinstudio.domain.feature.recording.repository.Recorder
import com.violinstudio.domain.feature.recording.repository.RecordingQueue
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/** Vuelve a poner en cola una toma `failed` (el motor la recoge solo). Cualquier otro estado no se toca. */
class RetryUploadUseCase @Inject constructor(private val queue: RecordingQueue) {
    suspend operator fun invoke(uid: String, id: String): Result<Unit> = queue.update(uid, id) {
        if (it.state == QueueState.FAILED) it.copy(state = QueueState.RECORDED, lastError = null) else it
    }.map { }
}

/**
 * Descarta una toma que aun no esta en el servidor (`recorded` o `failed`): borra el fichero y el elemento. Una toma
 * subiendo o ya subida no se descarta desde aqui (la borra el usuario desde sus grabaciones, A12).
 */
class DiscardUploadUseCase @Inject constructor(
    private val queue: RecordingQueue,
    private val recorder: Recorder
) {
    suspend operator fun invoke(uid: String, id: String): Result<Unit> {
        val item = queue.observe(uid).first().firstOrNull { it.id == id } ?: return Result.success(Unit)
        if (item.state != QueueState.RECORDED && item.state != QueueState.FAILED) {
            return Result.failure(IllegalStateException("La toma ya se esta subiendo"))
        }
        return queue.remove(uid, id).onSuccess { recorder.deleteTake(item.take) }
    }
}
