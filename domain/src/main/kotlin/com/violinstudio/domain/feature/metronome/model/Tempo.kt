package com.violinstudio.domain.feature.metronome.model

import com.violinstudio.domain.feature.metronome.failure.MetronomeFailure

/** Pulsos por minuto, entero entre 30 y 250. Fuera de rango lanza [MetronomeFailure.InvalidTempo]. */
@JvmInline
value class Tempo(val bpm: Int) {
    init {
        if (bpm !in MIN_BPM..MAX_BPM) throw MetronomeFailure.InvalidTempo
    }

    companion object {
        const val MIN_BPM = 30
        const val MAX_BPM = 250
        fun create(bpm: Int): Result<Tempo> = runCatching { Tempo(bpm) }

        val DEFAULT = Tempo(100)
    }
}
