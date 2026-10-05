package com.violinstudio.domain.feature.tuner.pitch

import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.tuner.model.Note
import com.violinstudio.domain.feature.tuner.model.ReferencePitch
import com.violinstudio.domain.feature.tuner.model.TuningTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.math.log2
import kotlin.math.pow

class TuningResolverTest {
    private val ref = ReferencePitch.DEFAULT

    private fun hz(midi: Int, cents: Double = 0.0, r: ReferencePitch = ref) =
        Note(midi).frequency(r) * 2.0.pow(cents / 1200.0)

    @Test
    fun `A4 mas 5 cents en violin es OpenString A4 con 5 cents`() {
        val r = TuningResolver.resolve(hz(69, 5.0), Instrument.VIOLIN, ref)
        assertEquals(TuningTarget.OpenString(Note(69), 2), r.target)
        assertEquals(5.0, r.cents, 1e-6)
    }

    @Test
    fun `elige la cuerda con menor valor absoluto de cents`() {
        val r = TuningResolver.resolve(hz(62, -20.0), Instrument.VIOLIN, ref)
        assertEquals(TuningTarget.OpenString(Note(62), 1), r.target)
        assertEquals(-20.0, r.cents, 1e-6)
    }

    @Test
    fun `cents con signo sin clamp hasta mas menos 600`() {
        val sharp = TuningResolver.resolve(hz(76, 600.0), Instrument.VIOLIN, ref, selected = 3)
        assertEquals(600.0, sharp.cents, 1e-6)
        val flat = TuningResolver.resolve(hz(55, -600.0), Instrument.VIOLIN, ref, selected = 0)
        assertEquals(-600.0, flat.cents, 1e-6)
    }

    @Test
    fun `el diapason 442 desplaza los objetivos`() {
        val r442 = ReferencePitch(442.0)
        val r = TuningResolver.resolve(hz(69, 0.0, r442), Instrument.VIOLIN, r442)
        assertEquals(0.0, r.cents, 1e-6)
        assertEquals(1200 * log2(442.0 / 440.0), TuningResolver.resolve(442.0, Instrument.VIOLIN, ref).cents, 1e-6)
    }

    @Test
    fun `cuerda fijada D4 con frecuencia cercana a A4 da cents grandes contra D4`() {
        val r = TuningResolver.resolve(440.0, Instrument.VIOLIN, ref, selected = 1)
        assertEquals(TuningTarget.OpenString(Note(62), 1), r.target)
        assertEquals(1200 * log2(440.0 / Note(62).frequency(ref)), r.cents, 1e-6)
        assertEquals(700.0, r.cents, 1e-6)
    }

    @Test
    fun `OTHER es cromatico a la nota mas cercana`() {
        val r = TuningResolver.resolve(hz(60, 10.0), Instrument.OTHER, ref)
        assertEquals(TuningTarget.Chromatic(Note(60)), r.target)
        assertEquals(10.0, r.cents, 1e-6)
    }

    @Test
    fun `cromatico con 442 desplaza la nota`() {
        val r442 = ReferencePitch(442.0)
        val r = TuningResolver.resolve(hz(69, 0.0, r442), Instrument.OTHER, r442)
        assertEquals(TuningTarget.Chromatic(Note(69)), r.target)
        assertEquals(0.0, r.cents, 1e-6)
    }

    @Test
    fun `OTHER ignora la cuerda seleccionada`() {
        val r = TuningResolver.resolve(hz(60), Instrument.OTHER, ref, selected = 2)
        assertEquals(TuningTarget.Chromatic(Note(60)), r.target)
    }

    @Test
    fun `indice seleccionado fuera de rango se ignora y resuelve en auto`() {
        val r = TuningResolver.resolve(hz(69), Instrument.VIOLIN, ref, selected = 9)
        assertEquals(TuningTarget.OpenString(Note(69), 2), r.target)
    }
}
