package com.violinstudio.domain.feature.tuner.pitch

import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.tuner.model.Note
import com.violinstudio.domain.feature.tuner.model.ReferencePitch
import com.violinstudio.domain.feature.tuner.model.StringSet
import com.violinstudio.domain.feature.tuner.model.TuningTarget
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.roundToInt

data class TuningResolution(val target: TuningTarget, val cents: Double)

object TuningResolver {
    /**
     * Resuelve el objetivo de [frequency]. Con [selected] (índice de cuerda, modo manual) usa esa cuerda;
     * si no, la de menor |cents|. [Instrument.OTHER] es cromático. Los cents no se acotan.
     */
    fun resolve(
        frequency: Double,
        instrument: Instrument,
        ref: ReferencePitch,
        selected: Int? = null,
    ): TuningResolution {
        val strings = StringSet.of(instrument)
            ?: return chromatic(frequency, ref)
        val index = selected?.takeIf { it in strings.indices }
            ?: strings.indices.minBy { abs(cents(frequency, strings[it], ref)) }
        val note = strings[index]
        return TuningResolution(TuningTarget.OpenString(note, index), cents(frequency, note, ref))
    }

    private fun chromatic(frequency: Double, ref: ReferencePitch): TuningResolution {
        val note = Note((Note.A4_MIDI + 12 * log2(frequency / ref.hz)).roundToInt())
        return TuningResolution(TuningTarget.Chromatic(note), cents(frequency, note, ref))
    }

    private fun cents(frequency: Double, note: Note, ref: ReferencePitch): Double =
        1200.0 * log2(frequency / note.frequency(ref))
}
