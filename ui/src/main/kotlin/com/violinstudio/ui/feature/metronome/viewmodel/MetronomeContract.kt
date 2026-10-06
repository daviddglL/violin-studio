package com.violinstudio.ui.feature.metronome.viewmodel

import com.violinstudio.domain.feature.metronome.model.BeatTick
import com.violinstudio.domain.feature.metronome.model.Tempo
import com.violinstudio.domain.feature.metronome.model.TimeSignature
import com.violinstudio.ui.commons.mvi.UiEffect
import com.violinstudio.ui.commons.mvi.UiIntent
import com.violinstudio.ui.commons.mvi.UiState

enum class MetronomeError { OUTPUT_UNAVAILABLE, UNKNOWN }

/** En 6/8 el [tempo] cuenta corcheas: hay un clic por BPM y la pantalla lo indica. */
data class MetronomeState(
    val tempo: Tempo = Tempo.DEFAULT,
    val signature: TimeSignature = TimeSignature.FOUR_FOUR,
    val isPlaying: Boolean = false,
    /** Tiempo que suena ahora (de los frames reproducidos); `null` parado o antes del primer clic. */
    val tick: BeatTick? = null,
    val error: MetronomeError? = null
) : UiState

sealed interface MetronomeIntent : UiIntent {
    /** Se acota a 30..250. */
    data class SetBpm(val bpm: Int) : MetronomeIntent

    data object Increment : MetronomeIntent

    data object Decrement : MetronomeIntent

    data class SetSignature(val signature: TimeSignature) : MetronomeIntent

    /** Toque del tap tempo: cambia el BPM solo desde el segundo toque de la secuencia. */
    data object Tap : MetronomeIntent

    /** Inicia o para; tras un fallo de la salida reintenta. */
    data object Toggle : MetronomeIntent

    /** Parada manual, `ON_STOP` o salir de la ruta: idempotente; no se reanuda sola al volver. */
    data object Stop : MetronomeIntent
}

/** Sin efectos de un solo uso: todo pasa por el estado. */
sealed interface MetronomeEffect : UiEffect

sealed interface MetronomeMutation {
    data class Bpm(val bpm: Int) : MetronomeMutation

    data class SignatureSelected(val signature: TimeSignature) : MetronomeMutation

    data object Started : MetronomeMutation

    data object Stopped : MetronomeMutation

    data class Beat(val tick: BeatTick) : MetronomeMutation

    data class Failed(val failure: Throwable) : MetronomeMutation
}
