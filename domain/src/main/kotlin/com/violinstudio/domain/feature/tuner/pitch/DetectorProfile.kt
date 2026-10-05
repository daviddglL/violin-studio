package com.violinstudio.domain.feature.tuner.pitch

import com.violinstudio.domain.feature.profile.model.Instrument
import kotlin.math.roundToInt

/** Parametros del detector por instrumento (sr = 44 100 Hz). */
data class DetectorProfile(
    val minHz: Double,
    val maxHz: Double,
    val frameSize: Int,
    val hop: Int,
    val sampleRate: Int = SAMPLE_RATE
) {
    /** Desfase maximo en muestras (periodo de [minHz]). */
    val maxLag: Int = (sampleRate / minHz).roundToInt()

    /** Desfase minimo en muestras (periodo de [maxHz]). */
    val minLag: Int = (sampleRate / maxHz).roundToInt()

    companion object {
        const val SAMPLE_RATE = 44_100

        fun of(instrument: Instrument): DetectorProfile = when (instrument) {
            Instrument.VIOLIN -> DetectorProfile(180.0, 2000.0, 2048, 1024)
            Instrument.VIOLA -> DetectorProfile(115.0, 1600.0, 2048, 1024)
            Instrument.CELLO -> DetectorProfile(58.0, 1200.0, 4096, 2048)
            Instrument.DOUBLE_BASS -> DetectorProfile(36.0, 600.0, 4096, 2048)
            Instrument.OTHER -> DetectorProfile(55.0, 2000.0, 4096, 2048)
        }
    }
}
