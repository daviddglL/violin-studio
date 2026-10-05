package com.violinstudio.domain.feature.tuner.model

sealed interface TunerReading {
    data object Idle : TunerReading

    data object NoPitch : TunerReading

    /** [cents] con signo y sin acotar; el tope visual es responsabilidad de ui. */
    data class Pitch(
        val frequency: Double,
        val target: TuningTarget,
        val cents: Double,
        val confidence: Double
    ) : TunerReading
}
