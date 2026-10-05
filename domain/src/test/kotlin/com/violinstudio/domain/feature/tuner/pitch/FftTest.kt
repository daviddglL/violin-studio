package com.violinstudio.domain.feature.tuner.pitch

import com.violinstudio.domain.testing.Signals
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class FftTest {
    @Test
    fun `impulso da espectro plano`() {
        val re = DoubleArray(64).also { it[0] = 1.0 }
        val im = DoubleArray(64)
        Fft(64).forward(re, im)
        for (k in 0 until 64) {
            assertEquals(1.0, re[k], 1e-12)
            assertEquals(0.0, im[k], 1e-12)
        }
    }

    @Test
    fun `seno en el bin k produce un pico en k y N menos k`() {
        val n = 1024
        val k = 37
        val re = Signals.sine(
            k * Signals.SAMPLE_RATE.toDouble() / n,
            n,
            amplitude = 1.0
        ).map { it.toDouble() }.toDoubleArray()
        val im = DoubleArray(n)
        Fft(n).forward(re, im)
        val mag = DoubleArray(n) { hypot(re[it], im[it]) }
        val peaks = mag.indices.filter { mag[it] > n / 4.0 }
        assertEquals(listOf(k, n - k), peaks)
        assertEquals(n / 2.0, mag[k], 1e-2)
    }

    @Test
    fun `la inversa recupera la senal`() {
        val n = 2048
        val original = Signals.whiteNoise(n, seed = 5).map { it.toDouble() }.toDoubleArray()
        val re = original.copyOf()
        val im = DoubleArray(n)
        val fft = Fft(n)
        fft.forward(re, im)
        fft.inverse(re, im)
        for (i in 0 until n) {
            assertEquals(original[i], re[i], 1e-9)
            assertEquals(0.0, im[i], 1e-9)
        }
    }

    @Test
    fun `exige tamano potencia de dos`() {
        assertThrows(IllegalArgumentException::class.java) { Fft(1000) }
        assertThrows(IllegalArgumentException::class.java) { Fft(0) }
    }

    @Test
    fun `exige arrays del tamano de la transformada`() {
        assertThrows(IllegalArgumentException::class.java) { Fft(8).forward(DoubleArray(4), DoubleArray(8)) }
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 8, 64])
    fun `delta en n=1 coincide con la DFT ingenua (signo del giro)`(size: Int) {
        val n = if (size > 1) 1 else 0
        val re = DoubleArray(size).also { it[n] = 1.0 }
        val im = DoubleArray(size)
        Fft(size).forward(re, im)
        for (k in 0 until size) {
            assertEquals(cos(2 * PI * k * n / size), re[k], 1e-12)
            assertEquals(-sin(2 * PI * k * n / size), im[k], 1e-12)
        }
    }
}
