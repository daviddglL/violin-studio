package com.violinstudio.ui.feature.metronome.viewmodel

import com.violinstudio.domain.feature.metronome.model.Tempo
import com.violinstudio.domain.feature.tuner.failure.TunerFailure

object MetronomeReducer {
    fun reduce(state: MetronomeState, mutation: MetronomeMutation): MetronomeState = when (mutation) {
        is MetronomeMutation.Bpm -> state.copy(tempo = Tempo(mutation.bpm.coerceIn(Tempo.MIN_BPM, Tempo.MAX_BPM)))
        is MetronomeMutation.SignatureSelected -> state.copy(signature = mutation.signature, tick = null)
        MetronomeMutation.Started -> state.copy(isPlaying = true, tick = null, error = null)
        MetronomeMutation.Stopped -> state.copy(isPlaying = false, tick = null)
        is MetronomeMutation.Beat -> state.copy(tick = mutation.tick)
        is MetronomeMutation.Failed -> state.copy(
            isPlaying = false,
            tick = null,
            error = if (mutation.failure is TunerFailure.AudioOutputUnavailable) {
                MetronomeError.OUTPUT_UNAVAILABLE
            } else {
                MetronomeError.UNKNOWN
            }
        )
    }
}
