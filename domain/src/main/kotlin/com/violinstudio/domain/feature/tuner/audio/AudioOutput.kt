package com.violinstudio.domain.feature.tuner.audio

import kotlinx.coroutines.flow.Flow

/** Formato fijo de la salida: PCM float mono. */
object PcmFormat {
    const val SAMPLE_RATE = 44_100
    const val BLOCK_SIZE = 1_024
}

/** Genera audio PCM mono (-1..1) bloque a bloque. La salida lo llama siempre desde un solo hilo y en orden. */
fun interface PcmGenerator {
    /**
     * Rellena TODO [buffer]. [startSample] es el indice, desde el inicio de la reproduccion, de `buffer[0]`:
     * un generador puede ser sin estado (el metronomo coloca los clics por indice) o llevar su propia fase.
     */
    fun fill(buffer: FloatArray, startSample: Long)

    /**
     * Gancho de cierre: al cancelar la reproduccion la salida lo llama UNA vez, en el mismo hilo que [fill] y
     * despues del ultimo `fill`, para pedir un ultimo bloque sin clic (p. ej. una rampa a cero). Devuelve `true`
     * si relleno [buffer] y hay que escribirlo; por defecto no hay bloque de cierre.
     */
    fun finish(buffer: FloatArray, startSample: Long): Boolean = false
}

/**
 * Salida de audio unica de la app (tono y metronomo). Solo suena una a la vez: la implementacion serializa las
 * llamadas a [play]; quien necesite exclusion (iniciar el metronomo detiene el tono) debe cancelar antes la
 * reproduccion anterior (y esperarla con un limite de tiempo).
 */
interface AudioOutput {
    /**
     * Reproduce [generator] mientras se colecta. Emite, cada ~20 ms y solo cuando la pista ya existe y suena, los
     * frames ya REPRODUCIDOS (no los escritos), contados desde el indice 0 de [PcmGenerator.fill]; los primeros
     * bloques se escriben antes de arrancar para no recortar el primer sonido. Cancelar pide el bloque de
     * [PcmGenerator.finish], espera (acotado, ~300 ms) a que suene y libera la salida una sola vez; si la escritura
     * esta atascada libera de inmediato. Los fallos llegan como
     * [com.violinstudio.domain.feature.tuner.failure.TunerFailure.AudioOutputUnavailable].
     */
    fun play(generator: PcmGenerator): Flow<Long>
}
