package com.violinstudio.ui.feature.tuner.viewmodel

import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.profile.usecase.ObserveProfileUseCase
import com.violinstudio.domain.feature.tuner.usecase.ObservePitchUseCase
import com.violinstudio.domain.feature.tuner.usecase.PlayReferenceToneUseCase
import com.violinstudio.ui.commons.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * La captura vive solo entre `Start`/`Resume` y `Stop`/[onCleared]: no hay servicio. Cada cambio de instrumento o
 * cuerda cancela la colección anterior antes de abrir la nueva (reinicia también el suavizador). Sin registros de
 * audio ni de lecturas.
 */
@HiltViewModel
class TunerViewModel @Inject constructor(
    observeProfile: ObserveProfileUseCase,
    private val observePitch: ObservePitchUseCase,
    private val playReference: PlayReferenceToneUseCase
) : MviViewModel<TunerState, TunerIntent, TunerEffect>(TunerState()) {
    private var captureJob: Job? = null
    private var toneJob: Job? = null
    private var resumeOnStart = false

    init {
        viewModelScope.launch {
            try {
                val profile = observeProfile().filterNotNull().first()
                onIntent(TunerIntent.ProfileLoaded(profile.instrument))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Sin perfil el afinador sigue en modo cromático.
            }
        }
    }

    override suspend fun handleIntent(intent: TunerIntent) {
        when (intent) {
            is TunerIntent.Start -> onStart(intent.granted, intent.rationale)
            TunerIntent.Stop -> onStop()
            is TunerIntent.Resume -> onResume(intent.granted, intent.rationale)
            is TunerIntent.PermissionResult -> {
                reduce(TunerMutation.PermissionResolved(intent.granted, intent.rationale))
                if (intent.granted) startCapture()
            }
            TunerIntent.ConfirmRationale -> {
                reduce(TunerMutation.RationaleHidden)
                sendEffect(TunerEffect.RequestMicPermission)
            }
            TunerIntent.DismissRationale -> reduce(TunerMutation.RationaleDismissed)
            TunerIntent.OpenAppSettings -> sendEffect(TunerEffect.OpenAppSettings)
            TunerIntent.ToggleReference -> onToggleReference()
            is TunerIntent.ProfileLoaded -> onProfileLoaded(intent.instrument)
            is TunerIntent.SelectInstrument -> {
                reduce(TunerMutation.InstrumentSelected(intent.instrument))
                restartIfListening()
                restartTone()
            }
            is TunerIntent.SelectString -> {
                reduce(TunerMutation.StringSelected(intent.index))
                restartIfListening()
                restartTone()
            }
        }
    }

    private suspend fun onStart(granted: Boolean, rationale: Boolean) {
        when (TunerReducer.startDecision(state.value, granted, rationale)) {
            StartDecision.CAPTURE -> if (!state.value.isListening) startCapture()
            StartDecision.RATIONALE -> reduce(TunerMutation.RationaleShown)
            StartDecision.REQUEST -> sendEffect(TunerEffect.RequestMicPermission)
            StartDecision.BLOCKED -> reduce(TunerMutation.PermissionResolved(granted = false, rationale = false))
        }
    }

    private suspend fun onResume(granted: Boolean, rationale: Boolean) {
        val resume = resumeOnStart
        resumeOnStart = false
        when {
            resume && granted -> startCapture()
            resume || granted -> reduce(TunerMutation.PermissionResolved(granted, rationale))
        }
    }

    private suspend fun onStop() {
        resumeOnStart = state.value.isListening
        stopCapture()
        stopTone()
        reduce(TunerMutation.ListeningStopped)
    }

    private suspend fun onProfileLoaded(instrument: Instrument) {
        val before = state.value.instrument
        reduce(TunerMutation.ProfileInstrument(instrument))
        if (state.value.instrument != before) restartIfListening()
    }

    private suspend fun restartIfListening() {
        if (state.value.isListening) startCapture()
    }

    private suspend fun startCapture() {
        stopCapture()
        stopTone()
        val current = state.value
        reduce(TunerMutation.ListeningStarted)
        captureJob = viewModelScope.launch {
            try {
                observePitch(current.instrument, current.config, current.selectedString).collect {
                    reduce(TunerMutation.Reading(it))
                }
                reduce(TunerMutation.ListeningStopped)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                resumeOnStart = false
                reduce(TunerMutation.Failed(e))
            }
        }
    }

    private suspend fun onToggleReference() {
        val current = state.value
        when {
            current.isPlayingReference -> stopTone()
            toneJob?.isActive == true -> Unit // ya esta arrancando: un segundo toque no cuenta
            !current.isListening -> startTone()
        }
    }

    private suspend fun restartTone() {
        if (toneJob?.isActive == true) startTone()
    }

    /** Una sola salida: cancela y espera la anterior (su rampa de salida) antes de abrir la nueva. */
    private suspend fun startTone() {
        stopTone()
        val current = state.value
        val note = current.selectedString?.let { current.strings?.getOrNull(it) } ?: return
        toneJob = viewModelScope.launch {
            try {
                playReference(note, current.config.referencePitch).collect {
                    reduce(TunerMutation.ReferencePlaying(true))
                }
                reduce(TunerMutation.ReferencePlaying(false))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reduce(TunerMutation.ReferenceFailed(e))
            }
        }
    }

    private suspend fun stopTone() {
        // Acotado: una salida que no termina de cancelarse no debe bloquear la cola de intenciones.
        withTimeoutOrNull(STOP_JOIN_TIMEOUT_MS) { toneJob?.cancelAndJoin() }
        toneJob = null
        reduce(TunerMutation.ReferencePlaying(false))
    }

    private suspend fun stopCapture() {
        captureJob?.cancelAndJoin()
        captureJob = null
    }

    private fun reduce(mutation: TunerMutation) = setState { TunerReducer.reduce(this, mutation) }

    private companion object {
        const val STOP_JOIN_TIMEOUT_MS = 500L
    }
}
