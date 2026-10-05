package com.violinstudio.domain.feature.metronome.failure

sealed class MetronomeFailure(message: String) : Exception(message) {
    /** Fallos de validacion sin traza de pila: son valores, no errores de programa. */
    override fun fillInStackTrace(): Throwable = this

    data object InvalidTempo : MetronomeFailure("Tempo fuera de rango (30..250 BPM)")
}
