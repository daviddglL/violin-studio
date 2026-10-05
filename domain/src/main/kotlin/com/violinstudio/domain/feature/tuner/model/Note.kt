package com.violinstudio.domain.feature.tuner.model

import kotlin.math.pow

/** Nota MIDI en temperamento igual (A4 = 69). */
@JvmInline
value class Note(val midi: Int) {
    val name: String get() = NAMES[Math.floorMod(midi, 12)]
    val octave: Int get() = Math.floorDiv(midi, 12) - 1

    fun frequency(ref: ReferencePitch): Double = ref.hz * 2.0.pow((midi - A4_MIDI) / 12.0)

    companion object {
        const val A4_MIDI = 69
        private val NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    }
}
