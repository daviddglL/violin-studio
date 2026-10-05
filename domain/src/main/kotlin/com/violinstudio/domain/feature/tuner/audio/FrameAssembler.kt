package com.violinstudio.domain.feature.tuner.audio

/**
 * Convierte chunks en frames solapados de [size] muestras cada [hop], con un unico buffer de
 * [size] muestras (memoria acotada: ventana de analisis, REQ-AUD-06). No es thread-safe.
 *
 * Uso: [offer] un chunk y [poll] hasta `null`. El frame devuelto es el buffer interno: solo es
 * valido hasta el siguiente [poll].
 */
class FrameAssembler(private val size: Int, private val hop: Int) {
    private val buffer = FloatArray(size)
    private var filled = 0
    private var pending: FloatArray = EMPTY
    private var position = 0

    init {
        require(hop in 1..size) { "hop must be in 1..size" }
    }

    fun offer(chunk: FloatArray) {
        check(position >= pending.size) { "previous chunk not fully consumed" }
        pending = chunk
        position = 0
    }

    fun poll(): FloatArray? {
        if (filled == size) {
            System.arraycopy(buffer, hop, buffer, 0, size - hop)
            filled = size - hop
        }
        val count = minOf(size - filled, pending.size - position)
        System.arraycopy(pending, position, buffer, filled, count)
        filled += count
        position += count
        if (position >= pending.size) pending = EMPTY.also { position = 0 }
        return if (filled == size) buffer else null
    }

    private companion object {
        val EMPTY = FloatArray(0)
    }
}
