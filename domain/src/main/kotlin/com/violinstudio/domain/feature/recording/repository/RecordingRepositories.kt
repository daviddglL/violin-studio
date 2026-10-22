package com.violinstudio.domain.feature.recording.repository

import com.violinstudio.domain.feature.recording.model.RecordedTake
import com.violinstudio.domain.feature.recording.model.RecordingAccount
import com.violinstudio.domain.feature.recording.model.RecordingOutcome
import com.violinstudio.domain.feature.recording.model.RecordingSpec
import kotlinx.coroutines.flow.StateFlow

/** Captura local de una toma (implementacion en data; solo con la pantalla visible, sin servicio en primer plano). */
interface Recorder {
    /** Abre el micro y empieza a escribir `{id}.part`. Los fallos llegan como `Result.failure(RecordingFailure)`. */
    suspend fun start(spec: RecordingSpec): Result<RecordingHandle>

    /**
     * Borra del disco una toma ya cerrada (`{id}.m4a`, o `{id}.mp4` si es video): la forma EXPLICITA de borrar lo que
     * el [RecordingHandle] ya entrego (descartar desde la UI, cola de subida, revocacion). Solo actua sobre la ruta
     * propia de la toma (`recordings/{uid}/{id}.{ext}`); idempotente.
     */
    suspend fun deleteTake(take: RecordedTake)
}

/**
 * Toma en curso. Propiedad del fichero: mientras graba es del handle; en cuanto [stop] devuelve la toma (o la parada
 * automatica publica [RecordingOutcome.AutoStopped]) la toma pasa a su receptor, que debe encolarla o borrarla con
 * [Recorder.deleteTake]. Desde ese momento el handle ya no toca el `.m4a`.
 */
interface RecordingHandle {
    /** `null` mientras graba; la parada automatica o un fallo del recorder lo fijan una sola vez. */
    val outcome: StateFlow<RecordingOutcome?>

    /** Cierra la toma y la deja como `.m4a`. Tras una parada automatica devuelve esa misma toma. */
    suspend fun stop(): Result<RecordedTake>

    /**
     * Para, libera el micro y borra el parcial mientras la toma sigue en curso; es lo que hace `ON_STOP`. Idempotente.
     * Tras una toma entregada ([stop] correcto o parada automatica) NO borra el `.m4a`: es un no-op (usar
     * [Recorder.deleteTake]), asi un `discard` tardio no destruye una grabacion ya encolada para subir.
     */
    suspend fun discard()
}

/** Estado de `users/{uid}` que decide si se puede grabar. */
interface RecordingAccountSource {
    suspend fun current(uid: String): Result<RecordingAccount>
}

/** Tomas del uid que esperan en la cola local de subida (cuentan para el cupo, REQ-REC-14). */
interface PendingUploads {
    suspend fun count(uid: String): Int
}
