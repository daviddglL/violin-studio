package com.violinstudio.ui.feature.intonation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.intonation.model.IntonationExercises
import com.violinstudio.domain.feature.intonation.model.IntonationSummary
import com.violinstudio.domain.feature.intonation.model.IntonationTracker
import com.violinstudio.domain.feature.intonation.usecase.ObserveIntonationBestUseCase
import com.violinstudio.domain.feature.intonation.usecase.SaveIntonationScoreUseCase
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.tuner.failure.TunerFailure
import com.violinstudio.domain.feature.tuner.model.TunerConfig
import com.violinstudio.domain.feature.tuner.model.TunerReading
import com.violinstudio.domain.feature.tuner.usecase.ObservePitchUseCase
import com.violinstudio.domain.feature.tuner.usecase.ObserveTunerConfigUseCase
import com.violinstudio.ui.commons.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Ejercicio de afinacion de la ruta (`exerciseId`). Escucha con el detector del afinador (violin, la referencia del
 * usuario) y lo puntua todo en el dispositivo: no se guarda audio ni lecturas, solo la mejor puntuacion. El micro se
 * usa solo entre `Start` y `Pause`/fin/[onCleared].
 */
@HiltViewModel
class IntonationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val observePitch: ObservePitchUseCase,
    observeConfig: ObserveTunerConfigUseCase,
    observeBest: ObserveIntonationBestUseCase,
    private val saveScore: SaveIntonationScoreUseCase
) : MviViewModel<IntonationState, IntonationIntent, IntonationEffect>(IntonationState()) {
    private val exercise = savedStateHandle.get<String>(EXERCISE_ID_KEY)?.let(IntonationExercises::byId)
    private var config = TunerConfig()
    private var tracker: IntonationTracker? = null
    private var captureJob: Job? = null

    init {
        exercise?.let { ex -> setState { copy(exercise = ex, expected = ex.notes.first()) } }
        viewModelScope.launch {
            observeConfig().catch { emit(TunerConfig()) }.collect { config = it }
        }
        viewModelScope.launch {
            observeBest().catch { emit(emptyMap()) }.collect { best ->
                exercise?.let { setState { copy(best = best[it.id]) } }
            }
        }
    }

    override suspend fun handleIntent(intent: IntonationIntent) {
        when (intent) {
            is IntonationIntent.Start -> onStart(intent.granted)
            is IntonationIntent.PermissionResult -> onPermission(intent.granted, intent.rationale)
            IntonationIntent.Pause -> stopCapture()
            IntonationIntent.Skip -> onSkip()
            IntonationIntent.Restart -> onRestart()
            IntonationIntent.OpenAppSettings -> sendEffect(IntonationEffect.OpenAppSettings)
        }
    }

    private suspend fun onStart(granted: Boolean) {
        val current = state.value
        if (exercise == null || current.summary != null || current.isListening) return
        when {
            granted -> startCapture()
            current.mic != IntonationMic.BLOCKED -> sendEffect(IntonationEffect.RequestMicPermission)
        }
    }

    private suspend fun onPermission(granted: Boolean, rationale: Boolean) {
        val mic = when {
            granted -> IntonationMic.OK
            rationale -> IntonationMic.DENIED
            else -> IntonationMic.BLOCKED
        }
        setState { copy(mic = mic) }
        if (granted) onStart(granted = true)
    }

    private suspend fun onSkip() {
        val current = tracker() ?: return
        if (current.finished) return
        current.skip()
        publish(current, cents = null)
        if (current.finished) {
            stopCapture()
            finish(current)
        }
    }

    private suspend fun onRestart() {
        stopCapture()
        tracker = null
        val ex = exercise ?: return
        setState {
            copy(index = 0, expected = ex.notes.first(), cents = null, summary = null, isNewBest = false, error = null)
        }
    }

    private suspend fun startCapture() {
        stopCapture()
        val current = tracker() ?: return
        setState { copy(isListening = true, error = null) }
        captureJob = viewModelScope.launch {
            try {
                observePitch(Instrument.VIOLIN, config, null).first { reading ->
                    val frequency = (reading as? TunerReading.Pitch)?.frequency
                    publish(current, current.onPitch(frequency))
                    current.finished
                }
                setState { copy(isListening = false) }
                finish(current)
            } catch (e: CancellationException) {
                throw e
            } catch (_: NoSuchElementException) {
                // La fuente termino sin acabar el ejercicio: se queda en pausa.
                setState { copy(isListening = false) }
            } catch (_: TunerFailure.MicPermissionDenied) {
                setState { copy(isListening = false, cents = null, mic = IntonationMic.DENIED) }
            } catch (e: Exception) {
                setState { copy(isListening = false, cents = null, error = errorOf(e)) }
            }
        }
    }

    private suspend fun stopCapture() {
        captureJob?.cancelAndJoin()
        captureJob = null
        setState { copy(isListening = false, cents = null) }
    }

    /** El tracker se crea al empezar, con la referencia vigente; un reinicio lo descarta. */
    private fun tracker(): IntonationTracker? {
        val ex = exercise ?: return null
        return tracker ?: IntonationTracker(ex, config.referencePitch).also { tracker = it }
    }

    private fun publish(current: IntonationTracker, cents: Double?) =
        setState { copy(index = current.index, expected = current.expected, cents = cents) }

    private suspend fun finish(current: IntonationTracker) {
        val ex = exercise ?: return
        val summary = IntonationSummary.of(current.results)
        val previous = state.value.best
        setState {
            copy(
                isListening = false,
                cents = null,
                summary = summary,
                isNewBest = previous == null || summary.score > previous
            )
        }
        // Si no se puede guardar, el resultado se ve igual: solo se pierde como mejor puntuacion.
        saveScore(ex.id, summary.score)
    }

    private fun errorOf(failure: Throwable) = when (failure) {
        TunerFailure.MicBusy -> IntonationError.MIC_BUSY
        TunerFailure.MicUnavailable -> IntonationError.MIC_UNAVAILABLE
        else -> IntonationError.UNKNOWN
    }

    companion object {
        /** Nombre del argumento de la ruta: `SavedStateHandle` lo recibe por nombre de propiedad. */
        const val EXERCISE_ID_KEY = "exerciseId"
    }
}
