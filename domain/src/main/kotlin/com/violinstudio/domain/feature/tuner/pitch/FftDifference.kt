package com.violinstudio.domain.feature.tuner.pitch

/**
 * Funcion diferencia de YIN `d(tau) = sum_{j<W} (x[j] - x[j+tau])^2`, con `W = frameSize - maxLag`,
 * calculada en O(N log N): `d = E0 + E(tau) - 2 r(tau)` con la correlacion cruzada `r` por FFT de
 * tamano 2N y las energias desplazadas por sumas acumuladas.
 */
class FftDifference(private val frameSize: Int, private val maxLag: Int) {
    private val window = frameSize - maxLag
    private val fft = Fft(2 * frameSize)
    private val aRe = DoubleArray(2 * frameSize)
    private val aIm = DoubleArray(2 * frameSize)
    private val bRe = DoubleArray(2 * frameSize)
    private val bIm = DoubleArray(2 * frameSize)
    private val prefix = DoubleArray(frameSize + 1)

    init {
        require(maxLag in 1 until frameSize) { "maxLag must be in 1 until frameSize" }
    }

    /** Devuelve `d[0..maxLag]`. No es seguro entre hilos (reutiliza buffers). */
    fun compute(frame: FloatArray): DoubleArray {
        require(frame.size >= frameSize) { "frame shorter than $frameSize" }
        val size = 2 * frameSize
        aRe.fill(0.0)
        aIm.fill(0.0)
        bRe.fill(0.0)
        bIm.fill(0.0)
        for (i in 0 until frameSize) {
            val v = frame[i].toDouble()
            bRe[i] = v
            if (i < window) aRe[i] = v
            prefix[i + 1] = prefix[i] + v * v
        }
        fft.forward(aRe, aIm)
        fft.forward(bRe, bIm)
        // conj(A) * B
        for (k in 0 until size) {
            val re = aRe[k] * bRe[k] + aIm[k] * bIm[k]
            val im = aRe[k] * bIm[k] - aIm[k] * bRe[k]
            aRe[k] = re
            aIm[k] = im
        }
        fft.inverse(aRe, aIm)
        val energy0 = prefix[window]
        return DoubleArray(maxLag + 1) { tau ->
            maxOf(0.0, energy0 + (prefix[tau + window] - prefix[tau]) - 2 * aRe[tau])
        }
    }
}
