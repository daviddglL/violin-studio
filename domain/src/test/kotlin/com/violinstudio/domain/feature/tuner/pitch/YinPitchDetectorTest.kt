package com.violinstudio.domain.feature.tuner.pitch

import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.testing.Signals
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class YinPitchDetectorTest : PitchDetectorContract() {
    override fun create(): PitchDetector = YinPitchDetector(DetectorProfile.of(Instrument.CELLO))

    private fun hz(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)

    private fun cents(measured: Double, expected: Double) = 1200.0 * ln(measured / expected) / ln(2.0)

    private fun shifted(f: Double, cents: Double) = f * 2.0.pow(cents / 1200.0)

    private val strings = mapOf(
        Instrument.VIOLIN to listOf(55, 62, 69, 76),
        Instrument.VIOLA to listOf(48, 55, 62, 69),
        Instrument.CELLO to listOf(36, 43, 50, 57),
        Instrument.DOUBLE_BASS to listOf(28, 33, 38, 43)
    )

    private fun detect(instrument: Instrument, frame: FloatArray) =
        YinPitchDetector(DetectorProfile.of(instrument)).detect(frame)

    private fun worstError(instrument: Instrument, freqs: List<Double>, signal: (Double, Int) -> FloatArray): Double {
        val n = DetectorProfile.of(instrument).frameSize
        return freqs.maxOf { f ->
            val e = detect(instrument, signal(f, n))
            assertNotNull(e, "$instrument $f Hz")
            abs(cents(e!!.frequency, f))
        }
    }

    @Test
    fun `senal por debajo de -50 dBFS da null`() {
        assertNull(create().detect(Signals.sine(130.0, 4096, amplitude = 0.004))) // rms ~0.0028
        assertNotNull(create().detect(Signals.sine(130.0, 4096, amplitude = 0.01)))
    }

    @Test
    fun `senal constante (solo continua) da null`() {
        assertNull(create().detect(FloatArray(4096) { 0.5f }))
    }

    @Test
    fun `buffer mas corto que N da null`() {
        assertNull(create().detect(Signals.sine(130.0, 4095)))
    }

    @ParameterizedTest
    @EnumSource(value = Instrument::class, names = ["VIOLIN", "VIOLA", "CELLO", "DOUBLE_BASS"])
    fun `cuerdas al aire dentro de 2 cents`(instrument: Instrument) {
        val worst = worstError(instrument, strings.getValue(instrument).map(::hz)) { f, n -> Signals.sine(f, n) }
        assertTrue(worst <= 2.0, "worst $worst")
    }

    @Test
    fun `barrido cromatico 55 a 1760 Hz dentro de 2 cents`() {
        val freqs = (33..105).map(::hz).filter { it in 55.0..1760.0 }
        val worst = worstError(Instrument.OTHER, freqs) { f, n -> Signals.sine(f, n) }
        assertTrue(worst <= 2.0, "worst $worst")
    }

    @ParameterizedTest
    @EnumSource(value = Instrument::class, names = ["VIOLIN", "VIOLA", "CELLO", "DOUBLE_BASS"])
    fun `cuerdas desafinadas se detectan dentro de 2 cents`(instrument: Instrument) {
        val offsets = listOf(-40.0, -25.0, -10.0, 7.0, 10.0, 25.0, 40.0)
        val freqs = strings.getValue(instrument).flatMap { m -> offsets.map { shifted(hz(m), it) } }
        val worst = worstError(instrument, freqs) { f, n -> Signals.sine(f, n) }
        assertTrue(worst <= 2.0, "worst $worst")
    }

    @ParameterizedTest
    @EnumSource(value = Instrument::class, names = ["CELLO", "DOUBLE_BASS"])
    fun `armonicos fuertes devuelven la fundamental`(instrument: Instrument) {
        val worst = worstError(instrument, strings.getValue(instrument).map(::hz)) { f, n ->
            Signals.harmonics(f, n, listOf(0.2, 0.24, 0.16))
        }
        assertTrue(worst <= 2.0, "worst $worst")
    }

    @ParameterizedTest
    @EnumSource(value = Instrument::class, names = ["VIOLIN", "VIOLA", "CELLO", "DOUBLE_BASS"])
    fun `ruido a 20 dB SNR dentro de 3 cents en 20 semillas`(instrument: Instrument) {
        val n = DetectorProfile.of(instrument).frameSize
        val errors = strings.getValue(instrument).map(::hz).flatMap { f ->
            (1L..20L).map { seed ->
                val e = detect(instrument, Signals.mix(Signals.sine(f, n), Signals.whiteNoise(n, seed), 20.0))
                assertNotNull(e, "$instrument $f Hz seed $seed")
                abs(cents(e!!.frequency, f))
            }
        }
        assertTrue(errors.max() <= 3.0, "worst ${errors.max()}")
    }

    @ParameterizedTest
    @EnumSource(value = Instrument::class, names = ["VIOLIN", "VIOLA", "CELLO", "DOUBLE_BASS", "OTHER"])
    fun `ruido blanco y silencio dan null en al menos 95 por ciento de frames`(instrument: Instrument) {
        val n = DetectorProfile.of(instrument).frameSize
        val detector = YinPitchDetector(DetectorProfile.of(instrument))
        val frames = (1L..100L).map { Signals.whiteNoise(n, seed = it, rms = 0.1) } + List(20) { Signals.silence(n) }
        val nulls = frames.count { detector.detect(it) == null }
        assertTrue(nulls >= frames.size * 0.95, "nulls $nulls")
    }

    @Test
    fun `confianza alta en seno limpio`() {
        val e = create().detect(Signals.sine(130.0, 4096))!!
        assertTrue(e.confidence > 0.95)
        assertEquals(130.0, e.frequency, 0.5)
    }

    @Test
    fun `E1 del contrabajo con ventana de 2,7 periodos es estable`() {
        val e1 = hz(28)
        val phases = listOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0)
        val worst = phases.maxOf { p ->
            val e = detect(Instrument.DOUBLE_BASS, Signals.sine(e1, 4096, phase = p))
            assertNotNull(e)
            abs(cents(e!!.frequency, e1))
        }
        assertTrue(worst <= 2.0, "worst $worst")
    }
}
