package com.violinstudio.ui.feature.tuner.viewmodel

import com.violinstudio.domain.feature.tuner.failure.TunerFailure
import com.violinstudio.domain.feature.tuner.failure.TunerField
import com.violinstudio.domain.feature.tuner.model.TunerReading

object TunerReducer {
    fun reduce(state: TunerState, mutation: TunerMutation): TunerState = when (mutation) {
        is TunerMutation.ProfileInstrument ->
            if (state.instrumentChosen) state else state.copy(instrument = mutation.instrument)
        is TunerMutation.ConfigLoaded -> state.copy(config = mutation.config)
        TunerMutation.ConfigOpened -> state.copy(showConfig = true, configError = null)
        TunerMutation.ConfigClosed -> state.copy(showConfig = false, configError = null)
        TunerMutation.ConfigErrorCleared -> state.copy(configError = null)
        is TunerMutation.ConfigFailed -> state.copy(configError = configErrorOf(mutation.failure))
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
        is TunerMutation.ReferenceFailed ->
            state.copy(isPlayingReference = false, error = TunerError.AUDIO_OUTPUT_UNAVAILABLE)
        is TunerMutation.ReferencePlaying ->
            state.copy(
                isPlayingReference = mutation.playing && !state.isListening,
                error = if (mutation.playing) null else state.error
            )
    }

    private fun configErrorOf(failure: Throwable) = when (failure) {
        is TunerFailure.InvalidConfig -> when (failure.field) {
            TunerField.REFERENCE_PITCH -> ConfigError.REFERENCE_PITCH
            TunerField.MAX_CENTS -> ConfigError.MAX_CENTS
            TunerField.LABEL -> ConfigError.LABEL
            TunerField.ID -> ConfigError.UNKNOWN
        }
        TunerFailure.DuplicatePresetLabel -> ConfigError.DUPLICATE_LABEL
        TunerFailure.PresetLimitReached -> ConfigError.PRESET_LIMIT
        TunerFailure.PresetNotFound -> ConfigError.PRESET_NOT_FOUND
        TunerFailure.StorageUnavailable -> ConfigError.STORAGE
        TunerFailure.NoSession -> ConfigError.NO_SESSION
        else -> ConfigError.UNKNOWN
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
            TunerFailure.MicBusy -> stopped.copy(error = TunerError.MIC_BUSY)
            TunerFailure.MicUnavailable -> stopped.copy(error = TunerError.MIC_UNAVAILABLE)
            is TunerFailure -> stopped.copy(error = TunerError.UNKNOWN)
            else -> stopped.copy(error = TunerError.MIC_UNAVAILABLE)
        }
    }
}
