package com.violinstudio.domain.feature.recording.failure

/** Fallos de grabacion. Ninguno lleva datos de la toma: solo el tipo (REQ-AUD-03). */
sealed class RecordingFailure(message: String) : Exception(message) {
    data object NoSession : RecordingFailure("Sin sesion")

    /** Flags apagados (`RECORDINGS_ENABLED`, o menor con `MINOR_RECORDING_ENABLED = false`): la UI oculta grabar. */
    data object Disabled : RecordingFailure("Las grabaciones estan desactivadas")

    data object ConsentRequired : RecordingFailure("Falta el consentimiento de grabacion")

    /** 20 grabaciones vivas o 19 + 1 en cola (REQ-REC-14). */
    data object QuotaReached : RecordingFailure("Cupo de grabaciones alcanzado")

    /** No se pudo leer la configuracion o la cuenta: falla cerrado, sin abrir el micro. */
    data object Unavailable : RecordingFailure("No se pudo comprobar el consentimiento")

    data object PermissionDenied : RecordingFailure("Permiso de microfono denegado")

    /** Micro ocupado o sin fuente: recuperable, sin fichero parcial. */
    data object MicUnavailable : RecordingFailure("Microfono no disponible")

    /** Ya hay una toma en curso (el micro es unico): no es un fallo del grabador, la UI no debe reintentar a ciegas. */
    data object Busy : RecordingFailure("Ya hay una grabacion en curso")

    /** La toma dura menos de `RecordingLimits.MIN_DURATION_MS`: el servidor la rechazaria; se borra en el cliente. */
    data object TooShort : RecordingFailure("La grabacion es demasiado corta")

    /** Fallo de `MediaRecorder` o de disco durante o al cerrar la toma: recuperable, sin fichero parcial. */
    data object RecorderFailed : RecordingFailure("Fallo del grabador")
}
