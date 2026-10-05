package com.violinstudio.domain.testing

import kotlin.math.log10
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SignalsTest {
    private fun zeroCrossingFrequency(x: FloatArray, sampleRate: Int): Double {
        var first = -1.0
        var last = -1.0
        var count = 0
        for (i in 1 until x.size) {
            if (x[i - 1] < 0f && x[i] >= 0f) {
                val pos = i - 1 + (-x[i - 1] / (x[i] - x[i - 1])).toDouble()
                if (first < 0) first = pos
                last = pos
                count++
            }
        }
        return (count - 1) * sampleRate / (last - first)
    }

    @ParameterizedTest
    @ValueSource(doubles = [41.2, 110.0, 440.0, 1000.0])
    fun `sine tiene la frecuencia pedida`(f: Double) {
        val x = Signals.sine(f, 16384)
        assertEquals(16384, x.size)
        assertEquals(f, zeroCrossingFrequency(x, Signals.SAMPLE_RATE), f * 1e-3)
    }

    @Test
    fun `sine respeta la amplitud`() {
        val x = Signals.sine(440.0, 4096, amplitude = 0.25)
        assertEquals(0.25, x.max().toDouble(), 1e-3)
    }

    @Test
    fun `harmonics suma parciales con sus amplitudes`() {
        val x = Signals.harmonics(100.0, 4096, listOf(1.0, 0.5))
        val fundamental = Signals.sine(100.0, 4096, amplitude = 1.0)
        val second = Signals.sine(200.0, 4096, amplitude = 0.5)
        for (i in x.indices) assertEquals(fundamental[i] + second[i], x[i], 1e-5f)
    }

    @Test
    fun `silence es todo ceros`() {
        assertTrue(Signals.silence(512).all { it == 0f })
        assertEquals(512, Signals.silence(512).size)
    }

    @Test
    fun `whiteNoise es determinista por semilla`() {
        assertArrayEquals(Signals.whiteNoise(1024, seed = 7), Signals.whiteNoise(1024, seed = 7))
        assertFalse(Signals.whiteNoise(1024, seed = 7).contentEquals(Signals.whiteNoise(1024, seed = 8)))
    }

    @Test
    fun `whiteNoise respeta el RMS pedido`() {
        assertEquals(0.1, Signals.rms(Signals.whiteNoise(65536, seed = 1, rms = 0.1)), 0.002)
    }

    @ParameterizedTest
    @ValueSource(doubles = [0.0, 20.0, 40.0])
    fun `mix deja el SNR pedido`(snr: Double) {
        val signal = Signals.sine(440.0, 16384, amplitude = 0.5)
        val noise = Signals.whiteNoise(16384, seed = 3)
        val mixed = Signals.mix(signal, noise, snr)
        val residual = FloatArray(signal.size) { mixed[it] - signal[it] }
        val measured = 20 * log10(Signals.rms(signal) / Signals.rms(residual))
        assertEquals(snr, measured, 0.01)
    }
}
