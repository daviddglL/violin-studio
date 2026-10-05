package com.violinstudio.ui.feature.tuner.viewmodel

import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.profile.usecase.ObserveProfileUseCase
import com.violinstudio.domain.feature.tuner.usecase.ObservePitchUseCase
import com.violinstudio.ui.commons.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * La captura vive solo entre `Start`/`Resume` y `Stop`/[onCleared]: no hay servicio. Cada cambio de instrumento o
 * cuerda cancela la colección anterior antes de abrir la nueva (reinicia también el suavizador). Sin registros de
 * audio ni de lecturas.
 */
@HiltViewModel
class TunerViewModel @Inject constructor(
    observeProfile: ObserveProfileUseCase,
    private val observePitch: ObservePitchUseCase
) : MviViewModel<TunerState, TunerIntent, TunerEffect>(TunerState()) {
    private var captureJob: Job? = null
    private var resumeOnStart = false

    init {
        viewModelScope.launch {
            try {
                val profile = observeProfile().filterNotNull().first()
                reduce(TunerMutation.ProfileInstrument(profile.instrument))
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
                reduce(TunerMutation.PermissionResolved(granted = false, rationale = true))
                sendEffect(TunerEffect.RequestMicPermission)
            }
            TunerIntent.DismissRationale -> reduce(TunerMutation.RationaleDismissed)
            TunerIntent.OpenAppSettings -> sendEffect(TunerEffect.OpenAppSettings)
            is TunerIntent.SelectInstrument -> {
                reduce(TunerMutation.InstrumentSelected(intent.instrument))
                restartIfListening()
            }
            is TunerIntent.SelectString -> {
                reduce(TunerMutation.StringSelected(intent.index))
                restartIfListening()
            }
        }
    }

    private suspend fun onStart(granted: Boolean, rationale: Boolean) {
        when (TunerReducer.startDecision(state.value, granted, rationale)) {
            StartDecision.CAPTURE -> startCapture()
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
        reduce(TunerMutation.ListeningStopped)
    }

    private suspend fun restartIfListening() {
        if (state.value.isListening) startCapture()
    }

    private suspend fun startCapture() {
        stopCapture()
        val current = state.value
        reduce(TunerMutation.ListeningStarted)
        captureJob = viewModelScope.launch {
            try {
                observePitch(current.instrument, current.config, current.selectedString).collect {
                    reduce(TunerMutation.Reading(it))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                resumeOnStart = false
                reduce(TunerMutation.Failed(e))
            }
        }
    }

    private suspend fun stopCapture() {
        captureJob?.cancelAndJoin()
        captureJob = null
    }

    override fun onCleared() {
        captureJob?.cancel()
        captureJob = null
        super.onCleared()
    }

    private fun reduce(mutation: TunerMutation) = setState { TunerReducer.reduce(this, mutation) }
}
