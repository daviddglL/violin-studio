package com.violinstudio.domain.feature.recording.repository

import com.violinstudio.domain.feature.recording.model.FinalizeStatus
import com.violinstudio.domain.feature.recording.model.QueuedRecording

/** Subida a Storage (implementacion en data). Los fallos llegan como `Result.failure(UploadFailure)`. */
interface RecordingUploader {
    /**
     * Sube la toma con la ruta y los metadatos de `RecordingUploadContract`, reanudando [QueuedRecording.sessionUri]
     * si existe. [onSession] recibe la URI de la sesion de subida en cuanto el SDK la conoce, para guardarla en la cola.
     */
    suspend fun upload(item: QueuedRecording, onSession: suspend (String) -> Unit): Result<Unit>

    /** `true` si el objeto ya esta en Storage: una subida terminada cuyo `uploaded` no llego a guardarse. */
    suspend fun exists(item: QueuedRecording): Result<Boolean>
}

/** Estado que `finalizeRecording` dejo para una toma subida (leido del servidor, nunca de la cache). */
interface FinalizeStatusSource {
    suspend fun status(uid: String, id: String): Result<FinalizeStatus>
}
