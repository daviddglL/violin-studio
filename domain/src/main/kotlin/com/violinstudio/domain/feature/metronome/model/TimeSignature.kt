package com.violinstudio.domain.feature.metronome.model

/** Compases soportados. [beats] es el numero de clics por compas; en 6/8 el BPM cuenta corcheas. */
enum class TimeSignature(val beats: Int, val label: String) {
    TWO_FOUR(2, "2/4"),
    THREE_FOUR(3, "3/4"),
    FOUR_FOUR(4, "4/4"),
    SIX_EIGHT(6, "6/8");

    /** El primer tiempo de cada compas es el acentuado. [beat] cuenta desde 0. */
    fun isAccent(beat: Long): Boolean = beat % beats == 0L
}
