package com.violinstudio.domain.feature.tuner.pitch

import kotlin.math.sqrt

/**
 * Detector de tono YIN. Usa un [FftDifference] propio, por lo que NO es seguro entre hilos:
 * una instancia por detector.
 */
class YinPitchDetector(
    private val profile: DetectorProfile,
    private val threshold: Double = DEFAULT_THRESHOLD
) : PitchDetector {
    private val difference = FftDifference(profile.frameSize, profile.maxLag)

    private val centered = FloatArray(profile.frameSize)

    override fun detect(frame: FloatArray): PitchEstimate? {
        if (frame.size < profile.frameSize || !centre(frame)) return null
        val d = difference.compute(centered)
        val cmndf = normalise(d) ?: return null
        val tau = firstValley(cmndf) ?: return null
        val refined = refine(d, tau)
        return PitchEstimate(profile.sampleRate / refined, (1.0 - cmndf[tau]).coerceIn(0.0, 1.0))
    }

    /** Copia el frame sin componente continua en [centered]; false si es invalido o esta bajo la puerta. */
    private fun centre(frame: FloatArray): Boolean {
        val n = profile.frameSize
        var mean = 0.0
        for (i in 0 until n) {
            if (!frame[i].isFinite()) return false
            mean += frame[i]
        }
        mean /= n
        var sum = 0.0
        for (i in 0 until n) {
            val v = frame[i] - mean
            centered[i] = v.toFloat()
            sum += v * v
        }
        return sqrt(sum / n) >= SILENCE_RMS
    }

    /** CMNDF `d'(tau) = d(tau) * tau / sum_{j<=tau} d(j)`; `null` si la energia acumulada es cero. */
    private fun normalise(d: DoubleArray): DoubleArray? {
        val out = DoubleArray(d.size)
        out[0] = 1.0
        var running = 0.0
        for (tau in 1 until d.size) {
            running += d[tau]
            if (running <= 0.0) return null
            out[tau] = d[tau] * tau / running
        }
        return out
    }

    /** Primer tau en [minLag, maxLag-1] bajo el umbral, descendiendo hasta el fondo del valle. */
    private fun firstValley(cmndf: DoubleArray): Int? {
        val last = profile.maxLag - 1
        var tau = profile.minLag
        while (tau <= last && cmndf[tau] >= threshold) tau++
        if (tau > last) return null
        while (tau < last && cmndf[tau + 1] < cmndf[tau]) tau++
        return tau
    }

    /**
     * Interpolacion parabolica sobre `d` por minimos cuadrados en tau +- h (h ~ tau / 8, simetrica
     * y por tanto sin sesgo para senos). Con h = 1 equivale a la parabola de tres puntos; el ajuste
     * ancho reduce el ruido en periodos largos (contrabajo).
     */
    private fun refine(d: DoubleArray, tau: Int): Double {
        val h = minOf(maxOf(1, tau / FIT_DIVISOR), tau, d.size - 1 - tau)
        var sy = 0.0
        var sxy = 0.0
        var sxxy = 0.0
        for (x in -h..h) {
            val y = d[tau + x]
            sy += y
            sxy += x * y
            sxxy += x.toDouble() * x * y
        }
        val s0 = 2.0 * h + 1
        val s2 = h * (h + 1) * (2.0 * h + 1) / 3.0
        val s4 = h * (h + 1) * (2.0 * h + 1) * (3.0 * h * h + 3 * h - 1) / 15.0
        val a = (s0 * sxxy - s2 * sy) / (s0 * s4 - s2 * s2)
        if (a <= 0.0) return tau.toDouble()
        return tau - (sxy / s2) / (2 * a)
    }

    companion object {
        const val DEFAULT_THRESHOLD = 0.15
        const val SILENCE_RMS = 0.003
        private const val FIT_DIVISOR = 32
    }
}
