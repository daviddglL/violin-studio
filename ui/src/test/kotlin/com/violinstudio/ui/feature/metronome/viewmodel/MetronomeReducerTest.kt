package com.violinstudio.ui.feature.metronome.viewmodel

import com.violinstudio.domain.feature.metronome.model.BeatTick
import com.violinstudio.domain.feature.metronome.model.Tempo
import com.violinstudio.domain.feature.metronome.model.TimeSignature
import com.violinstudio.domain.feature.tuner.failure.TunerFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class MetronomeReducerTest {
    private val playing = MetronomeState(isPlaying = true, tick = BeatTick(2, 2, false))

    private fun reduce(state: MetronomeState, m: MetronomeMutation) = MetronomeReducer.reduce(state, m)

    @ParameterizedTest
    @CsvSource("10,30", "30,30", "120,120", "250,250", "999,250")
    fun `el bpm se acota a 30-250`(requested: Int, expected: Int) {
        assertEquals(Tempo(expected), reduce(MetronomeState(), MetronomeMutation.Bpm(requested)).tempo)
    }

    @Test
    fun `cambiar de compas borra el tiempo que sonaba`() {
        val s = reduce(playing, MetronomeMutation.SignatureSelected(TimeSignature.SIX_EIGHT))
        assertEquals(TimeSignature.SIX_EIGHT, s.signature)
        assertNull(s.tick)
    }

    @Test
    fun `un tiempo llega al estado y parar lo borra`() {
        val tick = BeatTick(0, 0, true)
        val beating = reduce(MetronomeState(isPlaying = true), MetronomeMutation.Beat(tick))
        assertEquals(tick, beating.tick)
        val stopped = reduce(beating, MetronomeMutation.Stopped)
        assertFalse(stopped.isPlaying)
        assertNull(stopped.tick)
    }

    @Test
    fun `arrancar limpia el error`() {
        val s = reduce(MetronomeState(error = MetronomeError.UNKNOWN), MetronomeMutation.Started)
        assertTrue(s.isPlaying)
        assertNull(s.error)
    }

    @Test
    fun `un fallo de la salida para y distingue el motivo`() {
        val out = reduce(playing, MetronomeMutation.Failed(TunerFailure.AudioOutputUnavailable))
        assertFalse(out.isPlaying)
        assertEquals(MetronomeError.OUTPUT_UNAVAILABLE, out.error)
        assertEquals(MetronomeError.UNKNOWN, reduce(playing, MetronomeMutation.Failed(IllegalStateException())).error)
    }
}
