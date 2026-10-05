package com.violinstudio.domain.feature.tuner.pitch

import com.violinstudio.domain.testing.Signals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class FftDifferenceTest {
    /** Oraculo: d(tau) = sum_{j=0}^{W-1} (x[j] - x[j+tau])^2 con W = N - tauMax. */
    private fun direct(x: FloatArray, tauMax: Int): DoubleArray {
        val w = x.size - tauMax
        return DoubleArray(tauMax + 1) { tau ->
            var sum = 0.0
            for (j in 0 until w) {
                val d = x[j].toDouble() - x[j + tau]
                sum += d * d
            }
            sum
        }
    }

    private fun signal(kind: String, n: Int): FloatArray = when (kind) {
        "sine" -> Signals.sine(196.0, n)
        "noise" -> Signals.whiteNoise(n, seed = 11, rms = 0.3)
        else -> Signals.harmonics(98.0, n, listOf(1.0, 1.2, 0.8))
    }

    @ParameterizedTest
    @CsvSource(
        "sine,2048,245",
        "noise,2048,245",
        "harmonics,2048,383",
        "sine,4096,760",
        "noise,4096,1225",
        "harmonics,4096,802"
    )
    fun `la diferencia por FFT iguala a la directa`(kind: String, n: Int, tauMax: Int) {
        val x = signal(kind, n)
        val fast = FftDifference(n, tauMax).compute(x)
        val slow = direct(x, tauMax)
        assertEquals(tauMax + 1, fast.size)
        for (tau in slow.indices) assertEquals(slow[tau], fast[tau], 1e-6)
    }

    @Test
    fun `d(0) es cero`() {
        assertEquals(0.0, FftDifference(2048, 245).compute(Signals.sine(440.0, 2048))[0], 1e-6)
    }

    @Test
    fun `rechaza parametros o frames invalidos`() {
        assertThrows(IllegalArgumentException::class.java) { FftDifference(2048, 2048) }
        assertThrows(IllegalArgumentException::class.java) { FftDifference(2048, 0) }
        assertThrows(IllegalArgumentException::class.java) { FftDifference(2048, 245).compute(FloatArray(100)) }
    }

    @Test
    fun `silencio y senales constantes dan d finita y no negativa`() {
        val inputs =
            listOf(Signals.silence(2048), FloatArray(2048) { 0.7f }, Signals.sine(440.0, 2048, amplitude = 1.0))
        for (x in inputs) {
            val d = FftDifference(2048, 245).compute(x)
            assertTrue(d.all { it.isFinite() && it >= 0.0 })
        }
    }
}
