package com.violinstudio.domain.feature.tuner.failure

/** Valor de configuración al que se refiere un [TunerFailure.InvalidConfig]. */
enum class TunerField { REFERENCE_PITCH, MAX_CENTS, LABEL, ID }

sealed class TunerFailure(message: String) : Exception(message) {
    data object MicPermissionDenied : TunerFailure("Permiso de micrófono denegado")
    data object MicUnavailable : TunerFailure("Micrófono no disponible")
    data object MicBusy : TunerFailure("Micrófono en uso")
    data object AudioOutputUnavailable : TunerFailure("Salida de audio no disponible")
    data class InvalidConfig(val field: TunerField) : TunerFailure("Configuración del afinador inválida: $field")
    data object PresetLimitReached : TunerFailure("Límite de presets alcanzado")
    data object PresetNotFound : TunerFailure("Preset no encontrado")
    data object NoSession : TunerFailure("Sin sesión")

    /** El almacenamiento local falló (E/S); se reintenta o se ignora, nunca debe tumbar al afinador. */
    data object StorageUnavailable : TunerFailure("Almacenamiento local no disponible")
}
