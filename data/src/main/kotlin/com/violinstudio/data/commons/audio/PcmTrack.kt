package com.violinstudio.data.commons.audio

/** Costura fina sobre `AudioTrack` (PCM float mono, modo stream) para probar [AudioTrackOutput] sin framework. */
interface PcmTrack {
    fun play()

    /** Escritura bloqueante desde [offset]: devuelve las muestras escritas (puede ser < [size]) o un codigo de error negativo. */
    fun write(buffer: FloatArray, offset: Int, size: Int): Int

    /** Contador de frames de 32 bits de `AudioTrack` (sin signo: desborda a las ~27 h). */
    fun playbackHeadPosition(): Int

    fun pause()

    fun flush()

    fun release()
}

/** Crea una [PcmTrack] ya inicializada (buffer de al menos DOS bloques de [blockSize] muestras) o lanza. */
fun interface PcmTrackFactory {
    fun create(blockSize: Int): PcmTrack
}

/** Convierte el contador de 32 bits sin signo de `AudioTrack` en una posicion de 64 bits monotona. */
class PlaybackPosition {
    private var last = 0L
    private var wraps = 0L

    fun update(raw: Int): Long {
        val unsigned = raw.toLong() and MASK
        if (unsigned < last) wraps++
        last = unsigned
        return (wraps shl Int.SIZE_BITS) + unsigned
    }

    private companion object {
        const val MASK = 0xFFFFFFFFL
    }
}
