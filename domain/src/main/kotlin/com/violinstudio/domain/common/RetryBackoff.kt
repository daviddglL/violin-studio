package com.violinstudio.domain.common

import javax.inject.Inject

/** Espera exponencial entre reintentos: `initialMillis`, el doble, el doble... con tope `maxMillis`. */
class RetryBackoff(private val initialMillis: Long, private val maxMillis: Long) {
    @Inject
    constructor() : this(DEFAULT_INITIAL_MILLIS, DEFAULT_MAX_MILLIS)

    /** Espera previa al reintento número [attempt] (0 = el primer reintento). */
    fun delayFor(attempt: Int): Long = 0L

    companion object {
        const val DEFAULT_INITIAL_MILLIS = 1_000L
        const val DEFAULT_MAX_MILLIS = 30_000L
    }
}
