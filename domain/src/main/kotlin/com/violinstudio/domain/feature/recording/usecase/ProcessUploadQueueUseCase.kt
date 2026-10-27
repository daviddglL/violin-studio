package com.violinstudio.domain.feature.recording.usecase

import com.violinstudio.domain.feature.recording.model.FailReason
import com.violinstudio.domain.feature.recording.model.FinalizeStatus
import com.violinstudio.domain.feature.recording.model.QueueState
import com.violinstudio.domain.feature.recording.model.QueuedRecording
import com.violinstudio.domain.feature.recording.model.RecordingLimits
import com.violinstudio.domain.feature.recording.model.UploadDecision
import com.violinstudio.domain.feature.recording.model.UploadError
import com.violinstudio.domain.feature.recording.model.UploadFailure
import com.violinstudio.domain.feature.recording.model.UploadFailureClassifier
import com.violinstudio.domain.feature.recording.model.UploadPass
import com.violinstudio.domain.feature.recording.repository.FinalizeStatusSource
import com.violinstudio.domain.feature.recording.repository.Recorder
import com.violinstudio.domain.feature.recording.repository.RecordingAccountSource
import com.violinstudio.domain.feature.recording.repository.RecordingQueue
import com.violinstudio.domain.feature.recording.repository.RecordingUploader
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Una pasada por la cola de subida del uid (REQ-REC-04, REQ-REC-14, R1), toma a toma y en orden:
 * - `recorded`/`uploading`: se sube reanudando la sesion guardada; el fallo lo decide [UploadFailureClassifier].
 * - `uploaded`: se pregunta por el doc de finalize; `ready` borra la copia local y saca la toma de la cola.
 * - `done`: limpieza pendiente de una pasada interrumpida (fichero y elemento).
 * - `failed`: queda visible hasta que el usuario reintente o descarte.
 * Un fallo transitorio corta la pasada: sin red el resto fallaria igual.
 */
class ProcessUploadQueueUseCase @Inject constructor(
    private val queue: RecordingQueue,
    private val uploader: RecordingUploader,
    private val finalize: FinalizeStatusSource,
    private val accounts: RecordingAccountSource,
    private val recorder: Recorder
) {
    private enum class Step { PROGRESS, WAIT, STOP }

    suspend operator fun invoke(uid: String): UploadPass {
        var progressed = false
        for (item in queue.observe(uid).first()) {
            val step = when (item.state) {
                QueueState.RECORDED, QueueState.UPLOADING -> upload(item)
                QueueState.UPLOADED -> checkFinalized(item)
                QueueState.DONE -> drop(item)
                QueueState.FAILED -> Step.WAIT
            }
            if (step == Step.PROGRESS) progressed = true
            if (step == Step.STOP) break
        }
        val active = queue.observe(uid).first().any { it.state != QueueState.FAILED }
        return when {
            !active -> UploadPass.IDLE
            progressed -> UploadPass.PROGRESSED
            else -> UploadPass.STALLED
        }
    }

    private suspend fun upload(item: QueuedRecording): Step {
        val started = queue.update(item.take.uid, item.id) {
            it.copy(state = QueueState.UPLOADING, attempts = it.attempts + 1, lastError = null)
        }.getOrElse { return Step.STOP } ?: return Step.WAIT
        val result = uploader.upload(started) { uri ->
            queue.update(item.take.uid, item.id) { it.copy(sessionUri = uri) }
        }
        if (result.isSuccess) return markUploaded(started)
        val error = (result.exceptionOrNull() as? UploadFailure)?.error ?: UploadError.Other
        return when (val decision = decide(started, error)) {
            UploadDecision.Retry -> Step.STOP
            UploadDecision.AlreadyUploaded -> markUploaded(started)
            UploadDecision.ConsentRevoked -> drop(started)
            is UploadDecision.Fail -> fail(started, decision.reason)
        }
    }

    /**
     * Un 401/403 de Storage no dice por que: las reglas son create-only (el objeto ya subido tambien da 403), exigen el
     * consentimiento y la epoca vigentes y el cupo. Se pregunta en ese orden; si no se puede preguntar, se reintenta.
     */
    private suspend fun decide(item: QueuedRecording, error: UploadError): UploadDecision {
        if (error !is UploadError.Unauthorized) return UploadFailureClassifier.classify(error)
        when (uploader.exists(item).getOrNull()) {
            true -> return UploadDecision.AlreadyUploaded
            null -> return UploadDecision.Retry
            false -> Unit
        }
        val account = accounts.current(item.take.uid).getOrElse { return UploadDecision.Retry }
        val refined = when {
            !account.recordingConsentGranted || account.consentEpoch != item.take.consentEpoch ->
                UploadError.Unauthorized(consentRevoked = true)

            account.liveCount >= RecordingLimits.MAX_RECORDINGS_PER_OWNER -> UploadError.QuotaExceeded

            else -> error
        }
        return UploadFailureClassifier.classify(refined)
    }

    /** Con una sesion guardada el fallo puede ser de la sesion (caducada): se olvida y se reintenta de cero una vez. */
    private suspend fun fail(item: QueuedRecording, reason: FailReason): Step {
        val transform: (QueuedRecording) -> QueuedRecording = if (item.sessionUri != null) {
            { it.copy(sessionUri = null) }
        } else {
            { it.copy(state = QueueState.FAILED, lastError = reason) }
        }
        return queue.update(item.take.uid, item.id, transform).fold({ Step.PROGRESS }, { Step.STOP })
    }

    private suspend fun markUploaded(item: QueuedRecording): Step {
        val uploaded = queue.update(item.take.uid, item.id) {
            it.copy(state = QueueState.UPLOADED, sessionUri = null)
        }.getOrElse { return Step.STOP } ?: return Step.PROGRESS
        checkFinalized(uploaded)
        return Step.PROGRESS
    }

    private suspend fun checkFinalized(item: QueuedRecording): Step =
        when (val status = finalize.status(item.take.uid, item.id).getOrElse { return Step.WAIT }) {
            FinalizeStatus.Pending -> Step.WAIT

            FinalizeStatus.ConsentRejected -> drop(item)

            is FinalizeStatus.Rejected -> queue.update(item.take.uid, item.id) {
                it.copy(state = QueueState.FAILED, lastError = status.reason)
            }.fold({ Step.PROGRESS }, { Step.STOP })

            FinalizeStatus.Ready -> queue.update(item.take.uid, item.id) { it.copy(state = QueueState.DONE) }
                .fold({ drop(item) }, { Step.STOP })
        }

    /**
     * Saca la toma de la cola borrando antes su fichero: tras `done` (el servidor ya la tiene; una caida entre medias
     * repite la limpieza) o por R1 (consentimiento revocado o epoca caducada: no podra subir nunca).
     */
    private suspend fun drop(item: QueuedRecording): Step {
        deleteQuietly(item)
        return queue.remove(item.take.uid, item.id).fold({ Step.PROGRESS }, { Step.STOP })
    }

    private suspend fun deleteQuietly(item: QueuedRecording) {
        try {
            recorder.deleteTake(item.take)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Queda huerfano para el barrido de arranque.
        }
    }
}
