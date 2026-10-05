package com.violinstudio.data.feature.tuner.utils

import com.violinstudio.domain.feature.tuner.failure.TunerFailure

/**
 * Traduccion pura de los resultados de `AudioRecord` a [TunerFailure]. Las constantes replican las de
 * `android.media.AudioRecord`/`AudioManager` para que esta clase se pruebe sin framework.
 */
object AudioErrorMapper {
    const val STATE_INITIALIZED = 1
    const val RECORDSTATE_RECORDING = 3
    const val ERROR_INVALID_OPERATION = -3
    const val ERROR_DEAD_OBJECT = -6

    fun fromException(t: Throwable): TunerFailure = when (t) {
        is TunerFailure -> t
        is SecurityException -> TunerFailure.MicPermissionDenied
        else -> TunerFailure.MicUnavailable
    }

    fun fromInitState(state: Int): TunerFailure? = TunerFailure.MicUnavailable.takeIf { state != STATE_INITIALIZED }

    fun fromRecordingState(state: Int): TunerFailure? = TunerFailure.MicBusy.takeIf { state != RECORDSTATE_RECORDING }

    /** `read` devuelve el numero de muestras (>= 0) o un codigo de error negativo. */
    fun fromReadResult(result: Int): TunerFailure? = when {
        result >= 0 -> null
        result == ERROR_INVALID_OPERATION || result == ERROR_DEAD_OBJECT -> TunerFailure.MicBusy
        else -> TunerFailure.MicUnavailable
    }
}
