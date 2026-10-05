package com.violinstudio.domain.feature.tuner.model

import com.violinstudio.domain.feature.profile.model.Instrument

object StringSet {
    /** Cuerdas al aire de [instrument] de grave a agudo, o `null` si es cromático ([Instrument.OTHER]). */
    fun of(instrument: Instrument): List<Note>? = when (instrument) {
        Instrument.VIOLIN -> notes(55, 62, 69, 76)
        Instrument.VIOLA -> notes(48, 55, 62, 69)
        Instrument.CELLO -> notes(36, 43, 50, 57)
        Instrument.DOUBLE_BASS -> notes(28, 33, 38, 43)
        Instrument.OTHER -> null
    }

    private fun notes(vararg midi: Int) = midi.map(::Note)
}
