package com.violinstudio.ui.feature.metronome.viewmodel

import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.metronome.TapTempoCalculator
import com.violinstudio.domain.feature.metronome.usecase.MetronomeSession
import com.violinstudio.domain.feature.metronome.usecase.RunMetronomeUseCase
import com.violinstudio.ui.commons.di.MonotonicClock
import com.violinstudio.ui.commons.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Suena solo entre `Toggle`/`Resume` y `Stop`/destruccion del ViewModel (cancelar el `viewModelScope` para la
 * salida): no hay servicio. Cambiar el tempo es en vivo; cambiar el compas abre una sesion nueva tras cancelar y
 * esperar la anterior. Los intents se serializan en el hilo principal, el unico que toca el [TapTempoCalculator]
 * (no es thread-safe).
 *
 * Exclusion con el tono de referencia: no hay estado compartido. Tono y metronomo viven en pantallas hermanas de
 * Home; la ruta que se abandona detiene su audio al salir de la composicion (`Stop`) y el Mutex de la salida unica
 * serializa cualquier solape de la transicion.
 */
@HiltViewModel
class MetronomeViewModel @Inject constructor(
    private val run: RunMetronomeUseCase,
    @MonotonicClock clock: Clock
) : MviViewModel<MetronomeState, MetronomeIntent, MetronomeEffect>(MetronomeState()) {
    private val tapTempo = TapTempoCalculator(clock)
    private var session: MetronomeSession? = null
    private var playJob: Job? = null
    private var resumeOnStart = false

    override suspend fun handleIntent(intent: MetronomeIntent) {
        when (intent) {
            is MetronomeIntent.SetBpm -> setBpm(intent.bpm)
            MetronomeIntent.Increment -> setBpm(state.value.tempo.bpm + 1)
            MetronomeIntent.Decrement -> setBpm(state.value.tempo.bpm - 1)
            is MetronomeIntent.SetSignature -> {
                reduce(MetronomeMutation.SignatureSelected(intent.signature))
                if (state.value.isPlaying) start()
            }
            MetronomeIntent.Tap -> tapTempo.tap()?.let { setBpm(it.bpm) }
            MetronomeIntent.Toggle -> {
                resumeOnStart = false
                if (state.value.isPlaying) stop() else start()
            }
            MetronomeIntent.Stop -> {
                // ON_STOP y salir de la composicion llegan seguidos al rotar: el segundo no borra la reanudacion.
                resumeOnStart = resumeOnStart || state.value.isPlaying
                stop()
            }
            MetronomeIntent.Resume -> {
                val resume = resumeOnStart
                resumeOnStart = false
                if (resume) start()
            }
        }
    }

    private fun setBpm(bpm: Int) {
        reduce(MetronomeMutation.Bpm(bpm))
        session?.setTempo(state.value.tempo)
    }

    private suspend fun start() {
        stop()
        val current = state.value
        val next = run(current.tempo, current.signature)
        session = next
        reduce(MetronomeMutation.Started)
        playJob = viewModelScope.launch {
            try {
                next.ticks.collect { reduce(MetronomeMutation.Beat(it)) }
                reduce(MetronomeMutation.Stopped)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                resumeOnStart = false
                reduce(MetronomeMutation.Failed(e))
            }
        }
    }

    private suspend fun stop() {
        // Acotado: una salida que no termina de cancelarse no debe bloquear la cola de intenciones.
        withTimeoutOrNull(STOP_JOIN_TIMEOUT_MS) { playJob?.cancelAndJoin() }
        playJob = null
        session = null
        reduce(MetronomeMutation.Stopped)
    }

    private fun reduce(mutation: MetronomeMutation) = setState { MetronomeReducer.reduce(this, mutation) }

    private companion object {
        const val STOP_JOIN_TIMEOUT_MS = 500L
    }
}
