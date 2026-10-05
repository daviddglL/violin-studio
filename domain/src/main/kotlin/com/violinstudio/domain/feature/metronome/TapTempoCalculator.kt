package com.violinstudio.domain.feature.metronome

import com.violinstudio.domain.feature.metronome.model.Tempo
import java.time.Clock
import kotlin.math.roundToInt

/**
 * Tap tempo: mediana de los ultimos 4 intervalos entre toques (un toque atipico no la mueve), acotada al rango.
 * Reinicia si pasan mas de 2 s entre toques (2 s exactos, 30 BPM, siguen siendo alcanzables) o si el reloj
 * retrocede. [clock] DEBE ser monotono (p. ej. basado en `SystemClock.elapsedRealtime`) y NO el reloj de pared:
 * lo inyecta la capa de UI (M2b). No es thread-safe.
 */
class TapTempoCalculator(private val clock: Clock) {
    private val taps = ArrayDeque<Long>()

    /** Registra un toque. `null` mientras haya menos de 2 toques en la secuencia. */
    fun tap(): Tempo? {
        val now = clock.millis()
        if (taps.isNotEmpty() && (now < taps.last() || now - taps.last() > RESET_MS)) taps.clear()
        taps.addLast(now)
        while (taps.size > MAX_TAPS) taps.removeFirst()
        if (taps.size < 2) return null
        val median = median(taps.zipWithNext { a, b -> b - a })
        val bpm = if (median <= 0.0) Tempo.MAX_BPM else (60_000.0 / median).roundToInt()
        return Tempo(bpm.coerceIn(Tempo.MIN_BPM, Tempo.MAX_BPM))
    }

    private fun median(values: List<Long>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid].toDouble() else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    private companion object {
        const val RESET_MS = 2_000L
        const val MAX_TAPS = 5
    }
}
