package com.violinstudio.domain.feature.recording.repository

/**
 * Ficheros locales de una toma de video (4c), en el mismo directorio sin copia de seguridad que el audio. El original
 * sin difuminar solo existe mientras dura la toma: [sweepRaw] borra los que quedaron de una toma interrumpida.
 */
interface VideoFiles {
    fun rawPath(uid: String, id: String): String

    fun outputPath(uid: String, id: String): String

    /** Borra un fichero de toma de video del uid; ignora cualquier ruta que no sea de ese directorio. Idempotente. */
    suspend fun delete(uid: String, path: String)

    /** Borra los originales sin difuminar huerfanos del uid. */
    suspend fun sweepRaw(uid: String)
}
