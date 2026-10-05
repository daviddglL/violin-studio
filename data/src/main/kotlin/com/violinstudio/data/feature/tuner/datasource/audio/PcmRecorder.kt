package com.violinstudio.data.feature.tuner.datasource.audio

/** Costura fina sobre `AudioRecord` (PCM 16 bit mono) para probar [AudioRecordSource] sin micro. */
interface PcmRecorder {
    /** Empieza a capturar; lanza si el micro esta ocupado o no hay permiso. */
    fun start()

    /** Lectura bloqueante: muestras leidas (>= 0) o codigo de error negativo de `AudioRecord`. */
    fun read(buffer: ShortArray): Int

    fun stop()

    fun release()
}

/** Crea un [PcmRecorder] ya inicializado o lanza el fallo correspondiente. */
fun interface PcmRecorderFactory {
    fun create(chunkSize: Int): PcmRecorder
}
