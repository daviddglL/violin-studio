package com.violinstudio.domain.feature.tuner.pitch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PitchDetectorContractTest {
    /** Detector fake: devuelve la estimacion fija solo si el frame es valido; nunca lanza. */
    private class FakeDetector(private val estimate: PitchEstimate?) : PitchDetector {
        override fun detect(frame: FloatArray): PitchEstimate? =
            if (frame.isEmpty() || frame.any { it.isNaN() }) null else estimate
    }

    @Test
    fun `null significa sin tono`() {
        assertNull(FakeDetector(null).detect(FloatArray(8)))
    }

    @Test
    fun `entrada vacia o NaN no lanza y da null`() {
        val detector = FakeDetector(PitchEstimate(440.0, 0.9))
        assertNull(detector.detect(FloatArray(0)))
        assertNull(detector.detect(floatArrayOf(0.1f, Float.NaN)))
    }

    @Test
    fun `una estimacion valida se devuelve tal cual`() {
        val e = FakeDetector(PitchEstimate(440.0, 0.9)).detect(FloatArray(8) { 0.1f })
        assertNotNull(e)
        assertEquals(440.0, e!!.frequency)
        assertEquals(0.9, e.confidence)
    }

    @Test
    fun `PitchEstimate valida frecuencia y confianza`() {
        assertThrows(IllegalArgumentException::class.java) { PitchEstimate(0.0, 0.5) }
        assertThrows(IllegalArgumentException::class.java) { PitchEstimate(Double.NaN, 0.5) }
        assertThrows(IllegalArgumentException::class.java) { PitchEstimate(440.0, 1.5) }
        assertThrows(IllegalArgumentException::class.java) { PitchEstimate(440.0, -0.1) }
    }
}
