package com.violinstudio.domain.feature.tuner.model

import com.violinstudio.domain.feature.tuner.failure.TunerFailure
import com.violinstudio.domain.feature.tuner.failure.TunerField

/** Tope de desafinación mostrado, entre 25 y 200 cents. Fuera de rango lanza [TunerFailure.InvalidConfig]. */
@JvmInline
value class MaxCents(val value: Int) {
    init {
        if (value !in MIN..MAX) throw TunerFailure.InvalidConfig(TunerField.MAX_CENTS)
    }

    companion object {
        const val MIN = 25
        const val MAX = 200
        fun create(value: Int): Result<MaxCents> = runCatching { MaxCents(value) }

        val DEFAULT = MaxCents(50)
    }
}
