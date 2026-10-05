package com.violinstudio.domain.feature.tuner.pitch

import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.tuner.model.Note
import com.violinstudio.domain.feature.tuner.model.ReferencePitch
import com.violinstudio.domain.feature.tuner.model.TuningTarget
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sqrt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TuningResolverTest {
    private fun resolve(f: Double, i: Instrument, r: ReferencePitch, selected: Int? = null) =
        TuningResolver.resolve(f, i, r, selected)!!

    private val ref = ReferencePitch.DEFAULT

    private fun hz(midi: Int, cents: Double = 0.0, r: ReferencePitch = ref) =
        Note(midi).frequency(r) * 2.0.pow(cents / 1200.0)

    @Test
    fun `A4 mas 5 cents en violin es OpenString A4 con 5 cents`() {
        val r = resolve(hz(69, 5.0), Instrument.VIOLIN, ref)
        assertEquals(TuningTarget.OpenString(Note(69), 2), r.target)
        assertEquals(5.0, r.cents, 1e-6)
    }

    @Test
    fun `elige la cuerda con menor valor absoluto de cents`() {
        val r = resolve(hz(62, -20.0), Instrument.VIOLIN, ref)
        assertEquals(TuningTarget.OpenString(Note(62), 1), r.target)
        assertEquals(-20.0, r.cents, 1e-6)
    }

    @Test
    fun `cents con signo sin clamp hasta mas menos 600`() {
        val sharp = resolve(hz(76, 600.0), Instrument.VIOLIN, ref, selected = 3)
        assertEquals(600.0, sharp.cents, 1e-6)
        val flat = resolve(hz(55, -600.0), Instrument.VIOLIN, ref, selected = 0)
        assertEquals(-600.0, flat.cents, 1e-6)
    }

    @Test
    fun `el diapason 442 desplaza los objetivos`() {
        val r442 = ReferencePitch(442.0)
        val r = resolve(hz(69, 0.0, r442), Instrument.VIOLIN, r442)
        assertEquals(0.0, r.cents, 1e-6)
        assertEquals(1200 * log2(442.0 / 440.0), resolve(442.0, Instrument.VIOLIN, ref).cents, 1e-6)
    }

    @Test
    fun `cuerda fijada D4 con frecuencia cercana a A4 da cents grandes contra D4`() {
        val r = resolve(440.0, Instrument.VIOLIN, ref, selected = 1)
        assertEquals(TuningTarget.OpenString(Note(62), 1), r.target)
        assertEquals(1200 * log2(440.0 / Note(62).frequency(ref)), r.cents, 1e-6)
        assertEquals(700.0, r.cents, 1e-6)
    }

    @Test
    fun `OTHER es cromatico a la nota mas cercana`() {
        val r = resolve(hz(60, 10.0), Instrument.OTHER, ref)
        assertEquals(TuningTarget.Chromatic(Note(60)), r.target)
        assertEquals(10.0, r.cents, 1e-6)
    }

    @Test
    fun `cromatico con 442 desplaza la nota`() {
        val r442 = ReferencePitch(442.0)
        val r = resolve(hz(69, 0.0, r442), Instrument.OTHER, r442)
        assertEquals(TuningTarget.Chromatic(Note(69)), r.target)
        assertEquals(0.0, r.cents, 1e-6)
    }

    @Test
    fun `OTHER ignora la cuerda seleccionada`() {
        val r = resolve(hz(60), Instrument.OTHER, ref, selected = 2)
        assertEquals(TuningTarget.Chromatic(Note(60)), r.target)
    }

    @Test
    fun `indice seleccionado fuera de rango se ignora y resuelve en auto`() {
        val r = resolve(hz(69), Instrument.VIOLIN, ref, selected = 9)
        assertEquals(TuningTarget.OpenString(Note(69), 2), r.target)
    }

    @Test
    fun `empate equidistante entre D4 y A4 elige la cuerda de menor indice`() {
        val mean = sqrt(Note(62).frequency(ref) * Note(69).frequency(ref))
        val r = resolve(mean, Instrument.VIOLIN, ref)
        assertEquals(TuningTarget.OpenString(Note(62), 1), r.target)
        assertEquals(350.0, r.cents, 1e-6)
    }

    @Test
    fun `445 Hz en violin es A4 con unos 19,6 cents`() {
        val r = resolve(445.0, Instrument.VIOLIN, ref)
        assertEquals(TuningTarget.OpenString(Note(69), 2), r.target)
        assertEquals(19.6, r.cents, 0.1)
    }

    @Test
    fun `50 Hz en violin es G3 muy negativo sin clamp`() {
        val r = resolve(50.0, Instrument.VIOLIN, ref)
        assertEquals(TuningTarget.OpenString(Note(55), 0), r.target)
        assertEquals(1200 * log2(50.0 / Note(55).frequency(ref)), r.cents, 1e-6)
        assertTrue(r.cents < -600.0)
    }

    @Test
    fun `cromatico a mas 49 cents sigue en A4 y a mas 51 pasa a A#4`() {
        val a = resolve(hz(69, 49.0), Instrument.OTHER, ref)
        assertEquals(Note(69), a.target.note)
        assertEquals(49.0, a.cents, 1e-6)
        val b = resolve(hz(69, 51.0), Instrument.OTHER, ref)
        assertEquals(Note(70), b.target.note)
        assertEquals(-49.0, b.cents, 1e-6)
    }

    @Test
    fun `261,63 Hz es C4 en cromatico`() {
        assertEquals(Note(60), resolve(261.63, Instrument.OTHER, ref).target.note)
    }

    @Test
    fun `frecuencias no validas devuelven null en auto, manual y cromatico`() {
        val bad = listOf(Double.NaN, 0.0, -1.0, -440.0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
        for (f in bad) {
            assertNull(TuningResolver.resolve(f, Instrument.VIOLIN, ref), "auto $f")
            assertNull(TuningResolver.resolve(f, Instrument.VIOLIN, ref, selected = 1), "manual $f")
            assertNull(TuningResolver.resolve(f, Instrument.OTHER, ref), "cromatico $f")
        }
    }
}
