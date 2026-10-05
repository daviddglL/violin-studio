package com.violinstudio.data.feature.tuner.utils

/** Conversion PCM 16 bit -> float normalizado en [-1, 1). */
object Pcm16 {
    private const val SCALE = 32768f

    /** Convierte las primeras [count] muestras de [src] en un array nuevo (seguro de emitir por un flujo). */
    fun toFloats(src: ShortArray, count: Int = src.size): FloatArray = FloatArray(count) { src[it] / SCALE }
}
