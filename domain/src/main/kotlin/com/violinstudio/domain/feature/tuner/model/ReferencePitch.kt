package com.violinstudio.domain.feature.tuner.model

import com.violinstudio.domain.feature.tuner.failure.TunerFailure

/** Frecuencia de La4 en Hz, entre 415.0 y 466.0. Fuera de rango lanza [TunerFailure.InvalidConfig]. */
@JvmInline
value class ReferencePitch(val hz: Double) {
    init {
        if (hz.isNaN() || hz < MIN_HZ || hz > MAX_HZ) throw TunerFailure.InvalidConfig
    }

    companion object {
        const val MIN_HZ = 415.0
        const val MAX_HZ = 466.0
        val DEFAULT = ReferencePitch(440.0)
    }
}
