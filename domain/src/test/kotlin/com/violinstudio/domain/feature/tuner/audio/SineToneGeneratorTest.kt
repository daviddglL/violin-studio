package com.violinstudio.domain.feature.tuner.audio

import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.tuner.model.Note
import com.violinstudio.domain.feature.tuner.model.ReferencePitch
import com.violinstudio.domain.feature.tuner.pitch.DetectorProfile
import com.violinstudio.domain.feature.tuner.pitch.YinPitchDetector
import kotlin.math.abs
import kotlin.math.ln
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SineToneGeneratorTest {
    private val rampSamples = PcmFormat.SAMPLE_RATE / 50

    private fun block(generator: SineToneGenerator, size: Int, start: Long = 0): FloatArray =
        FloatArray(size).also { generator.fill(it, start) }

    @Test
    fun `A4 con referencia 442 se mide a 442 Hz con 2 cents de margen`() {
        val generator = SineToneGenerator(Note(69).frequency(ReferencePitch(442.0)))
        val profile = DetectorProfile.of(Instrument.VIOLIN)
        block(generator, rampSamples)
        val estimate = YinPitchDetector(profile).detect(block(generator, profile.frameSize))
        assertNotNull(estimate)
        assertEquals(0.0, 1200.0 * ln(estimate!!.frequency / 442.0) / ln(2.0), 2.0)
    }

    @Test
    fun `la fase es continua entre bloques`() {
        val whole = block(SineToneGenerator(440.0), 3000)
        val split = SineToneGenerator(440.0).let { g ->
            block(g, 1024) + block(g, 1024, 1024) + block(g, 952, 2048)
        }
        assertEquals(whole.toList(), split.toList())
    }

    @Test
    fun `empieza en silencio, sube en 20 ms y nunca supera la amplitud 0,5`() {
        val samples = block(SineToneGenerator(440.0), 4410)
        assertEquals(0f, samples[0])
        assertTrue(samples.all { abs(it) <= 0.5f })
        assertTrue(samples.drop(rampSamples).any { abs(it) > 0.49f })
    }

    @Test
    fun `fadeOut baja a cero en 20 ms sin saltos`() {
        val generator = SineToneGenerator(440.0)
        block(generator, 4410)
        generator.fadeOut()
        val tail = block(generator, rampSamples + 100, 4410)
        assertEquals(0f, abs(tail.last()))
        assertTrue(tail.toList().zipWithNext().all { (a, b) -> abs(b - a) < 0.05f })
    }
}
