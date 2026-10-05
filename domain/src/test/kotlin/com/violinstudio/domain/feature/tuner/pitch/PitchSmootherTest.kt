package com.violinstudio.domain.feature.tuner.pitch

import kotlin.math.log2
import kotlin.math.pow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PitchSmootherTest {
    private fun est(hz: Double) = PitchEstimate(hz, 0.9)

    private fun hz(cents: Double, base: Double = 440.0) = base * 2.0.pow(cents / 1200)

    private fun cents(hz: Double) = 1200 * log2(hz / 440.0)

    @Test
    fun `la mediana de 5 descarta un atipico`() {
        val s = PitchSmoother()
        listOf(0.0, 2.0, 1.0, 3.0).forEach { s.update(est(hz(it))) }
        val out = s.update(est(hz(80.0)))
        assertEquals(2.0, cents(out!!.frequency), 1e-6)
    }

    @Test
    fun `un salto de octava solo se acepta si persiste 3 frames`() {
        val s = PitchSmoother()
        repeat(5) { s.update(est(440.0)) }
        listOf(1, 2).forEach {
            assertEquals(440.0, s.update(est(880.0))!!.frequency, 1e-6)
        }
        assertEquals(880.0, s.update(est(880.0))!!.frequency, 1e-6)
    }

    @Test
    fun `un salto de octava aislado se ignora y reinicia la cuenta`() {
        val s = PitchSmoother()
        repeat(5) { s.update(est(440.0)) }
        s.update(est(880.0))
        s.update(est(880.0))
        s.update(est(440.0))
        assertEquals(440.0, s.update(est(880.0))!!.frequency, 1e-6)
    }

    @Test
    fun `un salto fuera de 1200 mas menos 50 no es histeresis`() {
        val s = PitchSmoother()
        repeat(5) { s.update(est(440.0)) }
        // 1300 cents: no es octava, entra a la mediana
        val out = (1..3).map { s.update(est(hz(1300.0))) }.last()
        assertEquals(1300.0, cents(out!!.frequency), 1e-6)
    }

    @Test
    fun `A4 a D4 cambia en como mucho 5 ventanas`() {
        val s = PitchSmoother()
        repeat(10) { s.update(est(440.0)) }
        val d4 = 293.6648
        val windows = (1..5).first { Math.abs(s.update(est(d4))!!.frequency - d4) < 1e-3 }
        assertEquals(3, windows)
    }

    @Test
    fun `silencio mantiene holdFrames ventanas y a la siguiente es NoPitch`() {
        val s = PitchSmoother(holdFrames = 3)
        repeat(5) { s.update(est(440.0)) }
        repeat(3) { assertEquals(440.0, s.update(null)!!.frequency, 1e-6) }
        assertNull(s.update(null))
        assertNull(s.update(null))
        // tras el silencio no se arrastra historia previa
        assertEquals(220.0, s.update(est(220.0))!!.frequency, 1e-6)
    }

    @Test
    fun `los candidatos de salto deben coincidir entre si`() {
        val s = PitchSmoother()
        repeat(5) { s.update(est(440.0)) }
        s.update(est(880.0))
        s.update(est(hz(1200.0 + 45.0)))
        // 2400 cents no coincide con el primer candidato (1200): reinicia la cuenta
        s.update(est(hz(2400.0)))
        assertEquals(440.0, s.update(est(hz(2400.0)))!!.frequency, 1e-6)
        assertEquals(hz(2400.0), s.update(est(hz(2400.0)))!!.frequency, 1e-6)
    }

    @Test
    fun `una estimacion reinicia la cuenta de silencio`() {
        val s = PitchSmoother()
        s.update(est(440.0))
        s.update(null)
        s.update(null)
        s.update(est(440.0))
        assertNotNull(s.update(null))
    }

    @Test
    fun `sin tono previo el silencio es NoPitch y holdFrames invalido falla`() {
        assertNull(PitchSmoother().update(null))
        assertThrows(IllegalArgumentException::class.java) { PitchSmoother(holdFrames = 0) }
    }

    @Test
    fun `con el historial a medias la mediana par promedia los centrales`() {
        val s = PitchSmoother()
        s.update(est(hz(0.0)))
        val out = s.update(est(hz(10.0)))
        assertEquals(5.0, cents(out!!.frequency), 1e-6)
    }
}
