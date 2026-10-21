package com.violinstudio.domain.feature.recording.repository

/**
 * Programa la subida de una toma encolada (implementacion en data con WorkManager: trabajo unico por grabacion).
 * No suspende ni devuelve error: la cola es la fuente de verdad y el arranque (B11) reprograma lo pendiente.
 */
interface RecordingUploadScheduler {
    /** Trabajo unico `rec-upload-{id}` con politica KEEP: programar dos veces no duplica la subida. */
    fun schedule(uid: String, id: String)

    /** Cancela el trabajo del id; si hay una subida en curso su tarea de Storage se detiene. */
    fun cancel(id: String)
}
