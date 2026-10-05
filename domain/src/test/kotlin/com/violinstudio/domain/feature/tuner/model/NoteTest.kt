package com.violinstudio.domain.feature.tuner.model

import kotlin.math.pow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class NoteTest {
    private fun expected(midi: Int, ref: Double) = ref * 2.0.pow((midi - 69) / 12.0)

    @ParameterizedTest
    @ValueSource(ints = [28, 36, 43, 55, 62, 69, 76, 100])
    fun `la frecuencia deriva del diapason`(midi: Int) {
        for (ref in listOf(415.0, 440.0, 442.0, 466.0)) {
            assertEquals(expected(midi, ref), Note(midi).frequency(ReferencePitch(ref)), 1e-9)
        }
    }

    @Test
    fun `A4 a 440 es 440 Hz`() {
        assertEquals(440.0, Note(69).frequency(ReferencePitch.DEFAULT), 1e-9)
    }

    @Test
    fun `E1 y C2 a 440`() {
        assertEquals(41.20, Note(28).frequency(ReferencePitch.DEFAULT), 0.01)
        assertEquals(65.41, Note(36).frequency(ReferencePitch.DEFAULT), 0.01)
    }

    @Test
    fun `442 desplaza todas las notas`() {
        val ref = ReferencePitch(442.0)
        for (m in 28..100) {
            val shifted = Note(m).frequency(ref)
            val base = Note(m).frequency(ReferencePitch.DEFAULT)
            assertTrue(shifted > base)
            assertEquals(442.0 / 440.0, shifted / base, 1e-12)
        }
    }

    @Test
    fun `nombre y octava`() {
        assertEquals("A", Note(69).name)
        assertEquals(4, Note(69).octave)
        assertEquals("C", Note(36).name)
        assertEquals(2, Note(36).octave)
        assertEquals("E", Note(28).name)
        assertEquals(1, Note(28).octave)
        assertEquals("F#", Note(66).name)
        assertEquals(4, Note(66).octave)
    }
}
