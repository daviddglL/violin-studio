package com.violinstudio.domain.feature.recording.model

/** Estado de una toma en la cola local (A14): `recorded -> uploading -> uploaded -> done | failed`. */
enum class QueueState { RECORDED, UPLOADING, UPLOADED, DONE, FAILED }

/** Motivo de un `failed` permanente. Solo el tipo, sin datos de la toma (REQ-AUD-03). */
enum class FailReason { PERMISSION, QUOTA, SIZE, UNKNOWN }

/**
 * Toma encolada para subir. [sessionUri] reanuda la subida de Storage; [lastError] es el [FailReason] del ultimo fallo.
 * [assignmentId] liga la toma a una tarea y [sharedWith] a un alumno (demo del profesor).
 */
data class QueuedRecording(
    val take: RecordedTake,
    val assignmentId: String? = null,
    val sharedWith: String? = null,
    val state: QueueState = QueueState.RECORDED,
    val attempts: Int = 0,
    val sessionUri: String? = null,
    val lastError: FailReason? = null,
    /** Instante (ms) en que el objeto quedo subido: arranca la espera de 24 h por el doc `ready`/`rejected`. */
    val uploadedAt: Long? = null,
    /** Ya se reintento una vez tras 24 h sin doc; el siguiente vencimiento pasa a `failed`. */
    val confirmRetried: Boolean = false,
    /** El dueno descarto la toma mientras subia: el worker limpia (fichero y, si el objeto llego, el doc, R3). */
    val cancelled: Boolean = false
) {
    val id: String get() = take.id

    override fun toString(): String = "QueuedRecording(state=$state, attempts=$attempts)"
}
