package com.violinstudio.domain.feature.metronome.model

import com.violinstudio.domain.feature.metronome.failure.MetronomeFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TempoTest {
    @Test
    fun `30 y 250 son validos`() {
        assertEquals(30, Tempo.create(30).getOrThrow().bpm)
        assertEquals(250, Tempo.create(250).getOrThrow().bpm)
    }

    @Test
    fun `29 y 251 se rechazan con InvalidTempo`() {
        listOf(29, 251).forEach {
            assertEquals(MetronomeFailure.InvalidTempo, Tempo.create(it).exceptionOrNull())
        }
    }

    @Test
    fun `el tempo inicial es 100`() {
        assertEquals(100, Tempo.DEFAULT.bpm)
    }

    @Test
    fun `los compases tienen 2, 3, 4 y 6 tiempos con el acento en el primero`() {
        assertEquals(listOf(2, 3, 4, 6), TimeSignature.entries.map { it.beats })
        assertEquals(listOf("2/4", "3/4", "4/4", "6/8"), TimeSignature.entries.map { it.label })
        assertTrue(TimeSignature.entries.all { it.isAccent(0L) && it.isAccent(it.beats.toLong()) && !it.isAccent(1L) })
    }
}
