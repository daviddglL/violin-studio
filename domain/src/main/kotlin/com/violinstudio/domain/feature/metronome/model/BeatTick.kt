package com.violinstudio.domain.feature.metronome.model

/** Tiempo que esta sonando: [beat] absoluto desde el inicio, [position] dentro del compas (desde 0) y si es acento. */
data class BeatTick(val beat: Long, val position: Int, val accent: Boolean)
