package com.violinstudio.data.feature.recording.datasource.media

import java.io.File

/**
 * Costura sobre `android.media.MediaRecorder` (unico sitio del proyecto que lo usa, REQ-AUD-01): permite probar la
 * logica de captura sin hardware. La implementacion real (`AndroidMediaRecorderFactory`) no tiene logica propia.
 */
interface MediaRecorderFactory {
    fun create(): MediaRecorderSession
}

/** Una toma: `start` configura AAC mono 44,1 kHz ~96 kbps en MP4, prepara y arranca; los fallos son excepciones. */
interface MediaRecorderSession {
    /** [maxDurationMs] es el limite exacto de la plataforma (`setMaxDuration`); lo avisa por [listener]. */
    fun start(output: File, maxDurationMs: Long, listener: Listener)

    /** Cierra el fichero (puede bloquear y lanza si no hay datos validos). */
    fun stop()

    fun release()

    interface Listener {
        fun onMaxDurationReached()

        fun onError()
    }
}

/** Directorio de las tomas del [uid] (en produccion, `noBackupFilesDir/recordings/{uid}`: sin copia de seguridad). */
fun interface RecordingsDirectory {
    fun forUid(uid: String): File
}
