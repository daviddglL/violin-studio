package com.violinstudio.data.feature.recording.datasource.media

/** Salida de un clip ya descargado a memoria. [play] es bloqueante (prepara el decodificador): llamar fuera de Main. */
interface AudioClipOutput {
    fun play(bytes: ByteArray, onDone: () -> Unit, onError: () -> Unit)

    fun stop()
}

/** Bytes del objeto de Storage (implementacion en `datasource/firebase`); falla si pasa de [maxBytes]. */
interface RecordingBytesSource {
    suspend fun read(objectPath: String, maxBytes: Long): ByteArray
}
