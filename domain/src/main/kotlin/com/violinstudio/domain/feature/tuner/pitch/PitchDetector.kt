package com.violinstudio.domain.feature.tuner.pitch

/** Estimacion de tono: [frequency] en Hz (> 0, finita) y [confidence] en 0..1. */
data class PitchEstimate(val frequency: Double, val confidence: Double) {
    init {
        require(frequency.isFinite() && frequency > 0.0) { "frequency must be finite and > 0" }
        require(confidence in 0.0..1.0) { "confidence must be in 0..1" }
    }
}

/** Detector de tono sobre un frame de muestras normalizadas (-1..1). */
interface PitchDetector {
    /** Devuelve `null` si no hay tono; nunca lanza por entrada vacia o con NaN. */
    fun detect(frame: FloatArray): PitchEstimate?
}
