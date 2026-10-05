package com.violinstudio.domain.feature.metronome

import com.violinstudio.domain.feature.metronome.model.Tempo
import java.time.Clock
import kotlin.math.roundToInt

/** Tap tempo: media de los ultimos 4 intervalos entre toques, acotada al rango. Reinicia tras 2 s sin tocar. */
class TapTempoCalculator(private val clock: Clock) {
    private val taps = ArrayDeque<Long>()

    /** Registra un toque. `null` mientras haya menos de 2 toques en la secuencia. */
    fun tap(): Tempo? {
        val now = clock.millis()
        if (taps.isNotEmpty() && now - taps.last() >= RESET_MS) taps.clear()
        taps.addLast(now)
        while (taps.size > MAX_TAPS) taps.removeFirst()
        if (taps.size < 2) return null
        val average = (taps.last() - taps.first()).toDouble() / (taps.size - 1)
        val bpm = if (average <= 0.0) Tempo.MAX_BPM else (60_000.0 / average).roundToInt()
        return Tempo(bpm.coerceIn(Tempo.MIN_BPM, Tempo.MAX_BPM))
    }

    private companion object {
        const val RESET_MS = 2_000L
        const val MAX_TAPS = 5
    }
}
