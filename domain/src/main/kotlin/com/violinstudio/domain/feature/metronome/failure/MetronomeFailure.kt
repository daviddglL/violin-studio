package com.violinstudio.domain.feature.metronome.failure

sealed class MetronomeFailure(message: String) : Exception(message) {
    data object InvalidTempo : MetronomeFailure("Tempo fuera de rango (30..250 BPM)")
}
