package com.violinstudio.domain.feature.tuner.pitch

import com.violinstudio.domain.feature.profile.model.Instrument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource

class DetectorProfileTest {
    @ParameterizedTest
    @CsvSource(
        "VIOLIN,180,2000,2048,1024,245",
        "VIOLA,115,1600,2048,1024,383",
        "CELLO,58,1200,4096,2048,760",
        "DOUBLE_BASS,36,600,4096,2048,1225",
        "OTHER,55,2000,4096,2048,802"
    )
    fun `tabla del diseno`(instrument: Instrument, minHz: Double, maxHz: Double, n: Int, hop: Int, tauMax: Int) {
        val p = DetectorProfile.of(instrument)
        assertEquals(minHz, p.minHz)
        assertEquals(maxHz, p.maxHz)
        assertEquals(n, p.frameSize)
        assertEquals(hop, p.hop)
        assertEquals(tauMax, p.maxLag)
        assertEquals(44_100, p.sampleRate)
    }

    @ParameterizedTest
    @EnumSource(Instrument::class)
    fun `el frame contiene al menos 3 periodos de la frecuencia minima`(instrument: Instrument) {
        val p = DetectorProfile.of(instrument)
        assertTrue(p.frameSize >= 3 * p.sampleRate / p.minHz, "$instrument")
    }

    @ParameterizedTest
    @EnumSource(Instrument::class)
    fun `la cadencia es al menos 10 actualizaciones por segundo`(instrument: Instrument) {
        val p = DetectorProfile.of(instrument)
        assertTrue(p.sampleRate.toDouble() / p.hop >= 10.0, "$instrument")
    }

    @ParameterizedTest
    @EnumSource(Instrument::class)
    fun `minLag y maxLag derivan de las frecuencias y caben en el frame`(instrument: Instrument) {
        val p = DetectorProfile.of(instrument)
        assertEquals(Math.round(p.sampleRate / p.maxHz).toInt(), p.minLag)
        assertTrue(p.minLag in 1 until p.maxLag)
        assertTrue(p.maxLag < p.frameSize)
    }

    @Test
    fun `hay un perfil por instrumento`() {
        Instrument.entries.forEach { DetectorProfile.of(it) }
    }
}
