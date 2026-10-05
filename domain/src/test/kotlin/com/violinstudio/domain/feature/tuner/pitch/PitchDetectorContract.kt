package com.violinstudio.domain.feature.tuner.pitch

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Contrato reutilizable: toda implementacion de [PitchDetector] debe pasar estas pruebas. */
abstract class PitchDetectorContract {
    abstract fun create(): PitchDetector

    @Test
    fun `entrada vacia da null sin lanzar`() {
        assertNull(create().detect(FloatArray(0)))
    }

    @Test
    fun `entrada con NaN o infinito da null sin lanzar`() {
        val detector = create()
        assertNull(detector.detect(FloatArray(4096) { if (it == 100) Float.NaN else 0.5f }))
        assertNull(detector.detect(FloatArray(4096) { if (it == 7) Float.POSITIVE_INFINITY else 0.5f }))
    }

    @Test
    fun `silencio da null`() {
        assertNull(create().detect(FloatArray(4096)))
    }
}
