package com.violinstudio.domain.feature.recording.model

/** Fallo de subida ya traducido por el worker (sin tipos de Firebase en `domain`). Sin datos de la toma. */
sealed interface UploadError {
    data object Network : UploadError

    data object RetryLimitExceeded : UploadError

    data object ObjectAlreadyExists : UploadError

    data object QuotaExceeded : UploadError

    data object TooLarge : UploadError

    data object Other : UploadError

    data class Http(val code: Int) : UploadError

    /** 401/403. [consentRevoked]: las reglas denegaron porque `recordingConsent` != granted o la epoca no es la vigente. */
    data class Unauthorized(val consentRevoked: Boolean) : UploadError
}

/** Que hace la cola con una subida fallida. */
sealed interface UploadDecision {
    data object Retry : UploadDecision

    /** El objeto ya esta en Storage (412 / `OBJECT_ALREADY_EXISTS`): pasa a `uploaded`. */
    data object AlreadyUploaded : UploadDecision

    /** R1: unico caso en el que el fichero local se borra de inmediato; el resto de `failed` queda visible. */
    data object ConsentRevoked : UploadDecision

    data class Fail(val reason: FailReason) : UploadDecision
}

/** Distingue transitorios (reintento) de permanentes (REQ-REC-04, REQ-REC-14, R1). Puro. */
object UploadFailureClassifier {
    private const val PRECONDITION_FAILED = 412
    private const val REQUEST_TIMEOUT = 408
    private const val TOO_MANY_REQUESTS = 429
    private val SERVER_ERRORS = 500..599

    fun classify(error: UploadError): UploadDecision = when (error) {
        UploadError.Network, UploadError.RetryLimitExceeded -> UploadDecision.Retry
        UploadError.ObjectAlreadyExists -> UploadDecision.AlreadyUploaded
        UploadError.QuotaExceeded -> UploadDecision.Fail(FailReason.QUOTA)
        UploadError.TooLarge -> UploadDecision.Fail(FailReason.SIZE)
        UploadError.Other -> UploadDecision.Fail(FailReason.UNKNOWN)
        is UploadError.Unauthorized ->
            if (error.consentRevoked) UploadDecision.ConsentRevoked else UploadDecision.Fail(FailReason.PERMISSION)
        is UploadError.Http -> when (error.code) {
            PRECONDITION_FAILED -> UploadDecision.AlreadyUploaded
            REQUEST_TIMEOUT, TOO_MANY_REQUESTS, in SERVER_ERRORS -> UploadDecision.Retry
            401, 403 -> UploadDecision.Fail(FailReason.PERMISSION)
            else -> UploadDecision.Fail(FailReason.UNKNOWN)
        }
    }
}
