package com.violinstudio.domain.testing

import java.util.Random
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Generadores de senales sinteticas deterministas para pruebas de audio (muestras en -1..1). */
object Signals {
    const val SAMPLE_RATE = 44_100

    fun sine(
        frequency: Double,
        n: Int,
        sampleRate: Int = SAMPLE_RATE,
        amplitude: Double = 0.5,
        phase: Double = 0.0
    ): FloatArray = FloatArray(n) { (amplitude * sin(2 * PI * frequency * it / sampleRate + phase)).toFloat() }

    /** Suma de parciales: `amplitudes[k]` es la del armonico k+1 de [fundamental]. */
    fun harmonics(fundamental: Double, n: Int, amplitudes: List<Double>, sampleRate: Int = SAMPLE_RATE): FloatArray {
        val out = FloatArray(n)
        amplitudes.forEachIndexed { k, a ->
            val partial = sine(fundamental * (k + 1), n, sampleRate, a)
            for (i in 0 until n) out[i] += partial[i]
        }
        return out
    }

    fun silence(n: Int): FloatArray = FloatArray(n)

    /** Ruido gaussiano con RMS [rms]; misma [seed] produce siempre la misma secuencia. */
    fun whiteNoise(n: Int, seed: Long, rms: Double = 0.1): FloatArray {
        val random = Random(seed)
        return FloatArray(n) { (random.nextGaussian() * rms).toFloat() }
    }

    fun rms(x: FloatArray): Double {
        if (x.isEmpty()) return 0.0
        var sum = 0.0
        for (v in x) sum += v.toDouble() * v
        return sqrt(sum / x.size)
    }

    /** Devuelve [signal] + [noise] reescalado para que RMS(signal)/RMS(ruido) sea [snrDb]. */
    fun mix(signal: FloatArray, noise: FloatArray, snrDb: Double): FloatArray {
        require(signal.size == noise.size) { "signal and noise must have the same size" }
        val noiseRms = rms(noise)
        val gain = if (noiseRms == 0.0) 0.0 else rms(signal) / (noiseRms * 10.0.pow(snrDb / 20.0))
        return FloatArray(signal.size) { (signal[it] + noise[it] * gain).toFloat() }
    }
}
