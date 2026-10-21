package com.violinstudio.data.feature.recording.datasource

import com.violinstudio.domain.feature.recording.model.UploadError
import kotlinx.coroutines.flow.Flow

/** Lo que Storage necesita para subir una toma. Ruta, ids y `sessionUri` no salen en `toString` (REQ-AUD-03). */
data class UploadRequest(
    val filePath: String,
    val objectPath: String,
    val contentType: String,
    val customMetadata: Map<String, String>,
    val sessionUri: String?
) {
    override fun toString(): String = "UploadRequest(contentType=$contentType)"
}

sealed interface UploadResult {
    data object Uploaded : UploadResult

    /** El adaptador ya tradujo la excepcion del SDK; la cancelacion NO llega aqui (se propaga como cancelacion). */
    data class Failed(val error: UploadError) : UploadResult
}

/** Estado del doc `users/{uid}/recordings/{id}` que crea `finalizeRecording`. */
enum class RemoteRecordingStatus { READY, REJECTED }

/** Consentimiento de grabacion vigente de `users/{uid}`: decide si un 403 es "consentimiento revocado" (R1). */
data class ConsentSnapshot(val granted: Boolean, val epoch: Long) {
    override fun toString(): String = "ConsentSnapshot(granted=$granted)"
}

/** Frontera con Firebase (Auth, Storage y Firestore) del worker de subida; los tests usan un fake. */
interface RecordingRemoteDataSource {
    /** uid de la sesion actual o `null` sin sesion. */
    fun currentUid(): String?

    /**
     * Sube (o reanuda con [UploadRequest.sessionUri]) el fichero. [onSession] recibe el `sessionUri` de cada progreso.
     * Cancelar la corrutina DEBE detener la tarea de Storage (lo hace quien cancela, desde su hilo).
     */
    suspend fun upload(request: UploadRequest, onSession: (String) -> Unit): UploadResult

    suspend fun objectExists(objectPath: String): Result<Boolean>

    /** Listener del doc: emite `null` mientras no existe y el estado cuando es `ready`/`rejected`. Falla si el listener falla. */
    fun observeStatus(uid: String, id: String): Flow<RemoteRecordingStatus?>

    suspend fun deleteDoc(uid: String, id: String): Result<Unit>

    suspend fun consent(uid: String): Result<ConsentSnapshot>
}
