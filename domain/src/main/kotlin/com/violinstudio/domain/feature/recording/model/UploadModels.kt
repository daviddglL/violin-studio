package com.violinstudio.domain.feature.recording.model

/** Fallo de [com.violinstudio.domain.feature.recording.repository.RecordingUploader]: solo el tipo, sin datos de la toma. */
class UploadFailure(val error: UploadError) : Exception("Fallo de subida")

/** Lo que `finalizeRecording` escribio en `users/{uid}/recordings/{id}` para una toma ya subida. */
sealed interface FinalizeStatus {
    /** Sin doc todavia: finalize aun no ha corrido (o reintenta). */
    data object Pending : FinalizeStatus

    data object Ready : FinalizeStatus

    /** `rejectReason == consent`: la epoca o el consentimiento ya no son vigentes (R1, se borra la toma local). */
    data object ConsentRejected : FinalizeStatus

    data class Rejected(val reason: FailReason) : FinalizeStatus
}

/** Resultado de una pasada por la cola. */
enum class UploadPass {
    /** No queda nada que subir ni que esperar. */
    IDLE,

    /** Alguna toma avanzo y queda trabajo: la siguiente pasada puede ir pronto. */
    PROGRESSED,

    /** Nada avanzo (sin red, finalize pendiente...): la siguiente pasada espera mas. */
    STALLED
}

/** Resultado de una ejecucion de [com.violinstudio.domain.feature.recording.usecase.DrainUploadQueueUseCase]. */
enum class DrainOutcome {
    /** Nada pendiente (o cambio el usuario: el programador lanza otra ejecucion para el nuevo). */
    DONE,

    /** Sigue habiendo trabajo que no avanza: el programador reintenta mas tarde con su propia espera. */
    RETRY
}
