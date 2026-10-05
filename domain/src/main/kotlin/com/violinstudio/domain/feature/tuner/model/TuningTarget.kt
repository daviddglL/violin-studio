package com.violinstudio.domain.feature.tuner.model

sealed interface TuningTarget {
    val note: Note

    /** Cuerda al aire; [index] es la posición en [StringSet.of] (0 = la más grave). */
    data class OpenString(override val note: Note, val index: Int) : TuningTarget

    data class Chromatic(override val note: Note) : TuningTarget
}
