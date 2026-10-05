package com.violinstudio.domain.feature.metronome

import com.violinstudio.domain.feature.tuner.audio.PcmFormat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClickSynthTest {
    private val sr = PcmFormat.SAMPLE_RATE

    private fun expected(i: Int, hz: Double, amp: Double) = amp * sin(2 * PI * hz * i / sr) * exp(-i / (0.008 * sr))

    @Test
    fun `los clics duran 30 ms`() {
        assertEquals(1_323, ClickSynth.accent.size)
        assertEquals(1_323, ClickSynth.normal.size)
    }

    @Test
    fun `acento 1500 Hz amplitud 0,9 y normal 1000 Hz amplitud 0,6 con envolvente exponencial`() {
        listOf(ClickSynth.accent to (1500.0 to 0.9), ClickSynth.normal to (1000.0 to 0.6)).forEach { (click, p) ->
            // Los ultimos 2 ms llevan una rampa a cero, fuera de la comprobacion de la formula.
            (0 until click.size - 90).forEach {
                assertEquals(expected(it, p.first, p.second), click[it].toDouble(), 1e-4, "i=$it")
            }
        }
    }

    @Test
    fun `no satura y la envolvente termina en cero`() {
        listOf(ClickSynth.accent, ClickSynth.normal).forEach { click ->
            assertTrue(click.all { abs(it) <= 1f })
            assertEquals(0f, abs(click.last()))
        }
        assertTrue(ClickSynth.accent.maxOf { abs(it) } > ClickSynth.normal.maxOf { abs(it) })
    }
}
