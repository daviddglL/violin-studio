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

/** Nombres validos de toma y de dueno (el nombre de fichero y de carpeta salen de aqui: nada de rutas relativas). */
internal object RecordingNames {
    val ID = Regex("[A-Za-z0-9-]{1,64}")
    val UID = Regex("[A-Za-z0-9]{1,128}")
}

/**
 * Borra todo `*.part` del [uid] (parciales de tomas que nadie cerro: proceso matado, fallo de cierre) y devuelve cuantos.
 * `AndroidAudioRecorder.start` lo hace sobre su propio directorio; la app puede llamarlo al arrancar (B8a/B11).
 */
fun RecordingsDirectory.sweepPartials(uid: String): Int {
    if (!RecordingNames.UID.matches(uid)) return 0
    val orphans = forUid(uid).listFiles { file -> file.isFile && file.name.endsWith(".part") }.orEmpty()
    return orphans.count { it.delete() }
}
