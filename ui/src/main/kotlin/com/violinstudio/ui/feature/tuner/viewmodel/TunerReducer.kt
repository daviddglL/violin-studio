package com.violinstudio.ui.feature.tuner.viewmodel

import com.violinstudio.domain.feature.tuner.failure.TunerFailure
import com.violinstudio.domain.feature.tuner.model.TunerReading

object TunerReducer {
    fun reduce(state: TunerState, mutation: TunerMutation): TunerState = when (mutation) {
        is TunerMutation.ProfileInstrument ->
            if (state.instrumentChosen) state else state.copy(instrument = mutation.instrument)
        is TunerMutation.InstrumentSelected ->
            state.copy(
                instrument = mutation.instrument,
                instrumentChosen = true,
                selectedString = null,
                reading = TunerReading.Idle
            )
        is TunerMutation.StringSelected -> {
            val count = state.strings?.size ?: 0
            val valid = mutation.index == null || mutation.index in 0 until count
            if (valid) state.copy(selectedString = mutation.index, reading = TunerReading.Idle) else state
        }
        is TunerMutation.PermissionResolved ->
            state.copy(mic = micOf(mutation.granted, mutation.rationale), showRationale = false)
        TunerMutation.RationaleShown -> state.copy(showRationale = true)
        TunerMutation.RationaleDismissed -> state.copy(showRationale = false, mic = MicState.DENIED)
        TunerMutation.RationaleHidden -> state.copy(showRationale = false)
        TunerMutation.ListeningStarted ->
            state.copy(
                isListening = true,
                isPlayingReference = false,
                mic = MicState.GRANTED,
                error = null,
                reading = TunerReading.Idle
            )
        TunerMutation.ListeningStopped -> state.copy(isListening = false, reading = TunerReading.Idle)
        is TunerMutation.Reading -> state.copy(reading = mutation.reading)
        is TunerMutation.Failed -> failed(state, mutation.failure)
        is TunerMutation.ReferencePlaying ->
            state.copy(
                isPlayingReference = mutation.playing && !state.isListening,
                error = if (mutation.playing) null else state.error
            )
    }

    /** Qué hace "Escuchar" según el permiso que lee la pantalla. */
    fun startDecision(state: TunerState, granted: Boolean, rationale: Boolean): StartDecision = when {
        granted -> StartDecision.CAPTURE
        rationale -> StartDecision.RATIONALE
        state.mic == MicState.UNKNOWN || state.mic == MicState.GRANTED -> StartDecision.REQUEST
        else -> StartDecision.BLOCKED
    }

    /** Sin rationale tras denegar ya no se puede volver a preguntar. */
    private fun micOf(granted: Boolean, rationale: Boolean) = when {
        granted -> MicState.GRANTED
        rationale -> MicState.DENIED
        else -> MicState.PERMANENTLY_DENIED
    }

    private fun failed(state: TunerState, failure: Throwable): TunerState {
        val stopped = state.copy(isListening = false, reading = TunerReading.Idle)
        return when (failure) {
            TunerFailure.MicPermissionDenied -> stopped.copy(mic = MicState.DENIED, error = null)
            TunerFailure.AudioOutputUnavailable ->
                state.copy(isPlayingReference = false, error = TunerError.AUDIO_OUTPUT_UNAVAILABLE)
            TunerFailure.MicBusy -> stopped.copy(error = TunerError.MIC_BUSY)
            TunerFailure.MicUnavailable -> stopped.copy(error = TunerError.MIC_UNAVAILABLE)
            is TunerFailure -> stopped.copy(error = TunerError.UNKNOWN)
            else -> stopped.copy(error = TunerError.MIC_UNAVAILABLE)
        }
    }
}
