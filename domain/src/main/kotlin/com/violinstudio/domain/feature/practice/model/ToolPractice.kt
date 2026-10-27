package com.violinstudio.domain.feature.practice.model

import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Practica hecha con una herramienta que se ofrece guardar: desde el primer arranque, con el tiempo activo sumado. */
data class ToolPractice(val startedAt: Instant, val durationSec: Int) {
    val minutes: Int get() = durationSec / SECONDS_PER_MINUTE

    private companion object {
        const val SECONDS_PER_MINUTE = 60
    }
}

/**
 * Tiempo activo de una herramienta (metronomo sonando, ejercicio de afinacion escuchando) durante una visita a su
 * pantalla: suma los tramos entre [start] y [stop] con reloj de pared. Nunca registra nada por si solo (REQ-PRA-02):
 * solo calcula lo que la pantalla ofrece guardar con [offer]. No es thread-safe: lo usa el hilo de los intents.
 */
class ToolPracticeTimer(private val clock: Clock) {
    private var firstStart: Instant? = null
    private var segmentStart: Instant? = null
    private var accumulatedSec = 0L

    fun start() {
        if (segmentStart != null) return
        val now = clock.instant()
        if (firstStart == null) firstStart = now
        segmentStart = now
    }

    fun stop() {
        val from = segmentStart ?: return
        accumulatedSec += Duration.between(from, clock.instant()).seconds.coerceAtLeast(0)
        segmentStart = null
    }

    /** Lo acumulado si no esta en marcha y llega a [MIN_OFFER_SEC] (recortado a 12 h); si no, `null`. */
    fun offer(): ToolPractice? {
        val startedAt = firstStart ?: return null
        if (segmentStart != null || accumulatedSec < MIN_OFFER_SEC) return null
        return ToolPractice(startedAt, accumulatedSec.coerceAtMost(PracticeRules.MAX_DURATION_SEC.toLong()).toInt())
    }

    /** Tras guardar o descartar: lo siguiente empieza de cero. */
    fun reset() {
        firstStart = null
        segmentStart = null
        accumulatedSec = 0
    }

    companion object {
        const val MIN_OFFER_SEC = 60
    }
}
