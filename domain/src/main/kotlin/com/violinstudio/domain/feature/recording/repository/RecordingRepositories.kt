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
}

/** Toma en curso. Debe pararse con [stop] o descartarse con [discard]; ambas liberan el micro. */
interface RecordingHandle {
    /** `null` mientras graba; la parada automatica o un fallo del recorder lo fijan una sola vez. */
    val outcome: StateFlow<RecordingOutcome?>

    /** Cierra la toma y la deja como `.m4a`. Tras una parada automatica devuelve esa misma toma. */
    suspend fun stop(): Result<RecordedTake>

    /** Para, libera el micro y borra el parcial (o la toma ya cerrada). Idempotente; es lo que hace `ON_STOP`. */
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
