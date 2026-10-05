package com.violinstudio.ui.feature.tuner.viewmodel

import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.tuner.model.Note
import com.violinstudio.domain.feature.tuner.model.StringSet
import com.violinstudio.domain.feature.tuner.model.TunerConfig
import com.violinstudio.domain.feature.tuner.model.TunerReading
import com.violinstudio.ui.commons.mvi.UiEffect
import com.violinstudio.ui.commons.mvi.UiIntent
import com.violinstudio.ui.commons.mvi.UiState
import kotlin.math.abs

enum class MicState { UNKNOWN, GRANTED, DENIED, PERMANENTLY_DENIED }

/** Fallos de micro (con reintento) y de salida de audio (sin reintento) que la pantalla muestra. */
enum class TunerError { MIC_BUSY, MIC_UNAVAILABLE, AUDIO_OUTPUT_UNAVAILABLE, UNKNOWN }

/** Fallo de la hoja de configuración: uno por campo inválido, más límite, no encontrado y almacenamiento. */
enum class ConfigError {
    REFERENCE_PITCH,
    MAX_CENTS,
    LABEL,
    DUPLICATE_LABEL,
    PRESET_LIMIT,
    PRESET_NOT_FOUND,
    STORAGE,
    NO_SESSION,
    UNKNOWN
}

data class TunerState(
    /** Arranca en el instrumento del perfil y se cambia solo aquí (D3): nunca se escribe en el perfil. */
    val instrument: Instrument = Instrument.OTHER,
    /** `true` cuando el usuario eligió instrumento; un perfil que llega después ya no lo pisa. */
    val instrumentChosen: Boolean = false,
    /** Cents sin acotar: el tope visual (`config.maxCents`) lo aplica la pantalla. */
    val reading: TunerReading = TunerReading.Idle,
    /** Índice de cuerda fijado a mano; `null` = auto-detección. */
    val selectedString: Int? = null,
    val config: TunerConfig = TunerConfig(),
    val mic: MicState = MicState.UNKNOWN,
    val showRationale: Boolean = false,
    val isListening: Boolean = false,
    val isPlayingReference: Boolean = false,
    val error: TunerError? = null,
    val showConfig: Boolean = false,
    val configError: ConfigError? = null
) : UiState {
    /** Cuerdas al aire del instrumento activo; `null` = modo cromático. */
    val strings: List<Note>? get() = StringSet.of(instrument)
    val isInTune: Boolean
        get() = (reading as? TunerReading.Pitch)?.let { abs(it.cents) <= IN_TUNE_CENTS } ?: false

    companion object {
        const val IN_TUNE_CENTS = 2.5
    }
}

sealed interface TunerIntent : UiIntent {
    /** El usuario pulsa "Escuchar"; [granted] y [rationale] los lee la pantalla con `MicPermissionChecker`. */
    data class Start(val granted: Boolean, val rationale: Boolean) : TunerIntent

    /** Parada manual o `ON_STOP`: libera el micro y recuerda si había que reanudar. */
    data object Stop : TunerIntent

    /** `ON_START`: reanuda solo si se paró por `ON_STOP` y el permiso sigue concedido. */
    data class Resume(val granted: Boolean, val rationale: Boolean) : TunerIntent

    data class PermissionResult(val granted: Boolean, val rationale: Boolean) : TunerIntent

    data object ConfirmRationale : TunerIntent

    data object DismissRationale : TunerIntent

    data object OpenAppSettings : TunerIntent

    /** Interno: el perfil llegó; se procesa en orden con el resto de intents. */
    data class ProfileLoaded(val instrument: Instrument) : TunerIntent

    /** Interno: llegó la config persistida; se procesa en orden con el resto de intents. */
    data class ConfigLoaded(val config: TunerConfig) : TunerIntent

    data object OpenConfig : TunerIntent

    data object CloseConfig : TunerIntent

    /** El usuario edita un campo: el error en línea deja de aplicar a lo que ya cambió. */
    data object ClearConfigError : TunerIntent

    /** Aplica referencia y tope a la vez; sin validar aquí: el caso de uso rechaza lo inválido sin escribir. */
    data class UpdateConfig(val referenceHz: Double, val maxCents: Int) : TunerIntent

    /** `id == null` crea un preset; con id edita ese preset. */
    data class SavePreset(val id: String?, val label: String, val referenceHz: Double, val maxCents: Int) : TunerIntent

    data class DeletePreset(val id: String) : TunerIntent

    data class SelectPreset(val id: String) : TunerIntent

    data class SelectInstrument(val instrument: Instrument) : TunerIntent

    /** Reproduce o detiene el tono de la cuerda elegida; ignorado sin cuerda o mientras se escucha. */
    data object ToggleReference : TunerIntent

    /** `null` = auto-detección. */
    data class SelectString(val index: Int?) : TunerIntent
}

sealed interface TunerEffect : UiEffect {
    data object RequestMicPermission : TunerEffect

    data object OpenAppSettings : TunerEffect
}

sealed interface TunerMutation {
    data class ProfileInstrument(val instrument: Instrument) : TunerMutation

    data class ConfigLoaded(val config: TunerConfig) : TunerMutation

    data object ConfigOpened : TunerMutation

    data object ConfigClosed : TunerMutation

    data object ConfigErrorCleared : TunerMutation

    data class ConfigFailed(val failure: Throwable) : TunerMutation

    data class InstrumentSelected(val instrument: Instrument) : TunerMutation

    data class StringSelected(val index: Int?) : TunerMutation

    data class PermissionResolved(val granted: Boolean, val rationale: Boolean) : TunerMutation

    data object RationaleShown : TunerMutation

    data object RationaleDismissed : TunerMutation

    data object RationaleHidden : TunerMutation

    data object ListeningStarted : TunerMutation

    data object ListeningStopped : TunerMutation

    data class Reading(val reading: TunerReading) : TunerMutation

    data class Failed(val failure: Throwable) : TunerMutation

    data class ReferencePlaying(val playing: Boolean) : TunerMutation

    /** Fallo de la salida de audio: nunca toca el micro ni la escucha. */
    data class ReferenceFailed(val failure: Throwable) : TunerMutation
}

enum class StartDecision { CAPTURE, RATIONALE, REQUEST, BLOCKED }
