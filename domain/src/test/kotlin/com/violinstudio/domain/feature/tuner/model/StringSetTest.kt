package com.violinstudio.domain.feature.tuner.model

import com.violinstudio.domain.feature.profile.model.Instrument
import java.util.stream.Stream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class StringSetTest {
    companion object {
        @JvmStatic
        fun sets(): Stream<Arguments> = Stream.of(
            Arguments.of(Instrument.VIOLIN, listOf("G3", "D4", "A4", "E5"), listOf(55, 62, 69, 76)),
            Arguments.of(Instrument.VIOLA, listOf("C3", "G3", "D4", "A4"), listOf(48, 55, 62, 69)),
            Arguments.of(Instrument.CELLO, listOf("C2", "G2", "D3", "A3"), listOf(36, 43, 50, 57)),
            Arguments.of(Instrument.DOUBLE_BASS, listOf("E1", "A1", "D2", "G2"), listOf(28, 33, 38, 43))
        )
    }

    @ParameterizedTest
    @MethodSource("sets")
    fun `cuerdas con etiqueta y orden grave a agudo`(instrument: Instrument, labels: List<String>, midis: List<Int>) {
        val notes = StringSet.of(instrument)!!
        assertEquals(labels, notes.map { "${it.name}${it.octave}" })
        assertEquals(midis, notes.map { it.midi })
        assertEquals(notes.sortedBy { it.midi }, notes)
    }

    @ParameterizedTest
    @MethodSource("sets")
    fun `442 desplaza cada cuerda por 442 entre 440`(instrument: Instrument, labels: List<String>, midis: List<Int>) {
        for (n in StringSet.of(instrument)!!) {
            assertEquals(
                442.0 / 440.0,
                n.frequency(ReferencePitch(442.0)) / n.frequency(ReferencePitch.DEFAULT),
                1e-12
            )
        }
    }

    @Test
    fun `frecuencias del violin a 440`() {
        val f = StringSet.of(Instrument.VIOLIN)!!.map { it.frequency(ReferencePitch.DEFAULT) }
        assertEquals(listOf(196.00, 293.66, 440.00, 659.26), f.map { Math.round(it * 100) / 100.0 })
    }

    @Test
    fun `OTHER no tiene cuerdas`() = assertNull(StringSet.of(Instrument.OTHER))
}
