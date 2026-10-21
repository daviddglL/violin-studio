package com.violinstudio.data.feature.recording.datasource.work

import com.violinstudio.data.feature.recording.datasource.RecordingRemoteDataSource
import com.violinstudio.data.feature.recording.datasource.RemoteRecordingStatus
import com.violinstudio.data.feature.recording.datasource.UploadRequest
import com.violinstudio.data.feature.recording.datasource.UploadResult
import com.violinstudio.domain.feature.recording.model.FailReason
import com.violinstudio.domain.feature.recording.model.QueueState
import com.violinstudio.domain.feature.recording.model.QueuedRecording
import com.violinstudio.domain.feature.recording.model.RecordingUploadContract
import com.violinstudio.domain.feature.recording.model.UploadDecision
import com.violinstudio.domain.feature.recording.model.UploadError
import com.violinstudio.domain.feature.recording.model.UploadFailureClassifier
import com.violinstudio.domain.feature.recording.repository.Recorder
import com.violinstudio.domain.feature.recording.repository.RecordingQueue
import java.io.File
import java.time.Clock
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Resultado de una ejecucion del worker: `DONE` -> success, `RETRY` -> retry con backoff, `FAILED` -> failure. */
enum class EngineResult { DONE, RETRY, FAILED }

/**
 * Logica del worker de subida (A14, REQ-REC-04/09, R1, R3), sin tipos de Android para probarla en la JVM.
 *
 * Una ejecucion: aislamiento por uid -> subida reanudable (guarda el `sessionUri` de cada progreso) -> espera breve del
 * doc `users/{uid}/recordings/{id}` (`ready`/`rejected` => borra fichero y elemento, que deja de contar para el cupo).
 * La espera de 24 h se reparte entre reintentos de WorkManager con `uploadedAt`; tras 24 h sin doc se reintenta una vez
 * y despues pasa a `failed`. Un elemento `cancelled` se limpia (R3). Ningun log: ni ruta, ni id, ni uid.
 */
class RecordingUploadEngine @Inject constructor(
    private val queue: RecordingQueue,
    private val remote: RecordingRemoteDataSource,
    private val recorder: Recorder,
    private val clock: Clock
) {
    suspend fun run(uid: String, id: String): EngineResult {
        if (remote.currentUid() != uid) return EngineResult.RETRY
        val item = queue.observe(uid).first().firstOrNull { it.id == id } ?: return EngineResult.DONE
        return when {
            item.cancelled -> cancelled(item)
            item.state == QueueState.DONE -> finish(item)
            item.state == QueueState.FAILED -> EngineResult.FAILED
            item.state == QueueState.UPLOADED -> confirm(item)
            else -> upload(item)
        }
    }

    private suspend fun upload(item: QueuedRecording): EngineResult {
        if (item.attempts >= MAX_ATTEMPTS || !File(item.take.path).isFile) return fail(item, FailReason.UNKNOWN)
        val started = queue.update(item.take.uid, item.id) {
            it.copy(state = QueueState.UPLOADING, attempts = it.attempts + 1)
        }.getOrElse { return EngineResult.RETRY } ?: return EngineResult.DONE
        if (started.cancelled) return cancelled(started)
        return when (val result = uploadPersistingSession(started)) {
            UploadResult.Uploaded -> markUploaded(started)
            is UploadResult.Failed -> onFailure(started, result.error, canRefresh = true)
        }
    }

    /** Cada `sessionUri` se guarda en la cola aunque la subida se cancele (el guardado no se cancela con ella). */
    private suspend fun uploadPersistingSession(item: QueuedRecording): UploadResult = coroutineScope {
        val sessions = Channel<String>(Channel.CONFLATED)
        val saver = launch(NonCancellable) {
            for (session in sessions) queue.update(item.take.uid, item.id) { it.copy(sessionUri = session) }
        }
        try {
            remote.upload(request(item), onSession = { sessions.trySend(it) })
        } finally {
            withContext(NonCancellable) {
                sessions.close()
                saver.join()
            }
        }
    }

    private fun request(item: QueuedRecording) = UploadRequest(
        filePath = item.take.path,
        objectPath = RecordingUploadContract.objectPath(item.take),
        contentType = RecordingUploadContract.CONTENT_TYPE,
        customMetadata = RecordingUploadContract.customMetadata(item.take, item.assignmentId),
        sessionUri = item.sessionUri
    )

    private suspend fun onFailure(started: QueuedRecording, error: UploadError, canRefresh: Boolean): EngineResult {
        // Estado ACTUAL de la cola: el `sessionUri` pudo guardarse durante esta misma ejecucion (W4).
        val item = queue.observe(started.take.uid).first().firstOrNull { it.id == started.id } ?: return EngineResult.DONE
        if (error is UploadError.Http && error.code in SESSION_GONE && item.sessionUri != null) {
            // La sesion reanudable caduco: se descarta y se empieza de cero en el siguiente intento.
            queue.update(item.take.uid, item.id) { it.copy(sessionUri = null) }
            return EngineResult.RETRY
        }
        val refined = refineAuthorization(item, error) ?: return EngineResult.RETRY
        return when (val decision = UploadFailureClassifier.classify(refined)) {
            UploadDecision.Retry -> EngineResult.RETRY
            UploadDecision.AlreadyUploaded -> markUploaded(item)
            UploadDecision.ConsentRevoked -> {
                finish(item)
                EngineResult.FAILED
            }
            is UploadDecision.Fail ->
                if (decision.reason == FailReason.PERMISSION) denied(item, canRefresh) else fail(item, decision.reason)
        }
    }

    /**
     * 403 con el consentimiento vigente. Las reglas son create-only: re-subir un objeto que ya existe (caida entre los
     * ultimos bytes y `uploaded`, o reintento del usuario) da 403, no 412, asi que primero se mira si el objeto esta.
     * Si no, un solo reintento con token refrescado (claim `consentOk` obsoleto) antes de `failed(PERMISSION)`.
     */
    private suspend fun denied(item: QueuedRecording, canRefresh: Boolean): EngineResult {
        val exists = remote.objectExists(RecordingUploadContract.objectPath(item.take)).getOrElse {
            return EngineResult.RETRY
        }
        if (exists) return markUploaded(item)
        if (!canRefresh) return fail(item, FailReason.PERMISSION)
        remote.refreshAuth().getOrElse { return EngineResult.RETRY }
        return when (val again = uploadPersistingSession(item)) {
            UploadResult.Uploaded -> markUploaded(item)
            is UploadResult.Failed -> onFailure(item, again.error, canRefresh = false)
        }
    }

    /** R1: un 401/403 es "consentimiento revocado" si ya no hay consentimiento o su epoca no es la de la toma. */
    private suspend fun refineAuthorization(item: QueuedRecording, error: UploadError): UploadError? {
        val denied = error is UploadError.Unauthorized && !error.consentRevoked ||
            error is UploadError.Http && error.code in DENIED
        if (!denied) return error
        val consent = remote.consent(item.take.uid).getOrNull() ?: return null
        val revoked = !consent.granted || consent.epoch != item.take.consentEpoch
        return if (revoked) UploadError.Unauthorized(consentRevoked = true) else error
    }

    private suspend fun markUploaded(item: QueuedRecording): EngineResult {
        val uploaded = queue.update(item.take.uid, item.id) {
            it.copy(state = QueueState.UPLOADED, sessionUri = null, uploadedAt = clock.millis(), lastError = null)
        }.getOrElse { return EngineResult.RETRY } ?: return EngineResult.DONE
        return confirm(uploaded)
    }

    private suspend fun confirm(item: QueuedRecording): EngineResult {
        val since = stampUploadedAt(item) ?: return EngineResult.RETRY
        if (awaitDoc(item) != null) return finish(item)
        return if (timedOut(since)) expire(item) else EngineResult.RETRY
    }

    /** Tras 24 h sin doc: un reintento y despues `failed` (el fichero se conserva, visible para reintentar/descartar). */
    private suspend fun expire(item: QueuedRecording): EngineResult {
        if (item.confirmRetried) return fail(item, FailReason.UNKNOWN)
        queue.update(item.take.uid, item.id) { it.copy(confirmRetried = true, uploadedAt = clock.millis()) }
        return EngineResult.RETRY
    }

    /** Hora de la subida; una cola sin ella (anterior a B7b) la fija ahora. `null` si no se pudo guardar. */
    private suspend fun stampUploadedAt(item: QueuedRecording): Long? {
        item.uploadedAt?.let { return it }
        val now = clock.millis()
        queue.update(item.take.uid, item.id) { it.copy(uploadedAt = now) }.getOrElse { return null }
        return now
    }

    private fun timedOut(since: Long) = clock.millis() - since >= CONFIRM_DEADLINE_MS

    /** Ventana corta de escucha; un fallo del listener cuenta como "sin doc todavia". */
    private suspend fun awaitDoc(item: QueuedRecording): RemoteRecordingStatus? = try {
        withTimeoutOrNull(CONFIRM_WINDOW_MS) {
            remote.observeStatus(item.take.uid, item.id).first { it != null }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** Elemento descartado por el dueno: fichero fuera siempre; el doc, si el objeto llego a subirse (R3). */
    private suspend fun cancelled(item: QueuedRecording): EngineResult {
        if (item.state != QueueState.UPLOADING && item.state != QueueState.UPLOADED) return finish(item)
        val uploaded = item.state == QueueState.UPLOADED ||
            remote.objectExists(RecordingUploadContract.objectPath(item.take)).getOrElse { return EngineResult.RETRY }
        if (!uploaded) return awaitInFlight(item)
        deleteFile(item)
        val since = stampUploadedAt(item) ?: return EngineResult.RETRY
        if (awaitDoc(item) != null) {
            remote.deleteDoc(item.take.uid, item.id).getOrElse { return EngineResult.RETRY }
            return removeItem(item)
        }
        return if (timedOut(since)) removeItem(item) else EngineResult.RETRY
    }

    /**
     * Descartado con la subida posiblemente en vuelo (el trabajo se cancela, pero una tarea puede aterrizar a la vez):
     * el fichero se borra ya y el elemento espera [CANCEL_GRACE_MS] por si aparece el objeto (entonces, R3).
     */
    private suspend fun awaitInFlight(item: QueuedRecording): EngineResult {
        deleteFile(item)
        val since = stampUploadedAt(item) ?: return EngineResult.RETRY
        return if (clock.millis() - since >= CANCEL_GRACE_MS) removeItem(item) else EngineResult.RETRY
    }

    private suspend fun finish(item: QueuedRecording): EngineResult {
        deleteFile(item)
        return removeItem(item)
    }

    private suspend fun removeItem(item: QueuedRecording): EngineResult =
        queue.remove(item.take.uid, item.id).fold({ EngineResult.DONE }, { EngineResult.RETRY })

    private suspend fun deleteFile(item: QueuedRecording) {
        try {
            recorder.deleteTake(item.take)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Queda huerfano: lo barre el arranque (B11).
        }
    }

    private suspend fun fail(item: QueuedRecording, reason: FailReason): EngineResult {
        queue.update(item.take.uid, item.id) {
            it.copy(state = QueueState.FAILED, lastError = reason, sessionUri = null)
        }
        return EngineResult.FAILED
    }

    companion object {
        const val MAX_ATTEMPTS = 10
        /** Tras descartar con la subida en vuelo, cuanto se espera a que aterrice antes de dar el elemento por limpio. */
        const val CANCEL_GRACE_MS = 10L * 60 * 1000

        /** Cuanto espera una ejecucion al listener antes de ceder el hilo al reintento de WorkManager. */
        const val CONFIRM_WINDOW_MS = 30_000L
        const val CONFIRM_DEADLINE_MS = 24L * 60 * 60 * 1000
        private val SESSION_GONE = setOf(404, 410)
        private val DENIED = setOf(401, 403)
    }
}
