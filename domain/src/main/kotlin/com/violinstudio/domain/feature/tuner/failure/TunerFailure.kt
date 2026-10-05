package com.violinstudio.domain.feature.tuner.failure

sealed class TunerFailure(message: String) : Exception(message) {
    data object MicPermissionDenied : TunerFailure("Permiso de micrófono denegado")
    data object MicUnavailable : TunerFailure("Micrófono no disponible")
    data object MicBusy : TunerFailure("Micrófono en uso")
    data object AudioOutputUnavailable : TunerFailure("Salida de audio no disponible")
    data object InvalidConfig : TunerFailure("Configuración del afinador inválida")
    data object PresetLimitReached : TunerFailure("Límite de presets alcanzado")
}
