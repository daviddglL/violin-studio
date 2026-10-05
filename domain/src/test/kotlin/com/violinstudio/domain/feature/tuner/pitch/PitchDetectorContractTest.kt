package com.violinstudio.domain.feature.tuner.pitch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FakePitchDetectorContractTest : PitchDetectorContract() {
    override fun create(): PitchDetector = FakeDetector(PitchEstimate(440.0, 0.9))

    /** Devuelve la estimacion fija solo para frames validos y no silenciosos; nunca lanza. */
    private class FakeDetector(private val estimate: PitchEstimate) : PitchDetector {
        override fun detect(frame: FloatArray): PitchEstimate? =
            if (frame.isEmpty() || frame.any { !it.isFinite() } || frame.all { it == 0f }) null else estimate
    }

    @Test
    fun `una estimacion valida se devuelve tal cual`() {
        val e = create().detect(FloatArray(8) { 0.1f })
        assertNotNull(e)
        assertEquals(440.0, e!!.frequency)
        assertEquals(0.9, e.confidence)
    }
}

class PitchEstimateTest {
    @Test
    fun `valida frecuencia y confianza`() {
        assertThrows(IllegalArgumentException::class.java) { PitchEstimate(0.0, 0.5) }
        assertThrows(IllegalArgumentException::class.java) { PitchEstimate(Double.NaN, 0.5) }
        assertThrows(IllegalArgumentException::class.java) { PitchEstimate(440.0, 1.5) }
        assertThrows(IllegalArgumentException::class.java) { PitchEstimate(440.0, -0.1) }
    }
}
