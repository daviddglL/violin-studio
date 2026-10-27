package com.violinstudio.data.feature.recording.utils

import com.violinstudio.domain.feature.recording.model.UploadError

/**
 * Traduce un fallo de Firebase Storage a [UploadError] sin tipos del SDK en `domain`. Recibe los campos de
 * `StorageException` (codigo, HTTP y si la causa es de red) para poder probarse en JVM.
 */
object StorageErrorMapper {
    // Valores de `StorageException.ERROR_*` (estables en la API publica del SDK).
    const val ERROR_QUOTA_EXCEEDED = -13013
    const val ERROR_NOT_AUTHENTICATED = -13020
    const val ERROR_NOT_AUTHORIZED = -13021
    const val ERROR_RETRY_LIMIT_EXCEEDED = -13030

    private const val PRECONDITION_FAILED = 412
    private const val PAYLOAD_TOO_LARGE = 413
    private const val HTTP_ERRORS = 400

    fun map(errorCode: Int, httpCode: Int, networkCause: Boolean): UploadError = when {
        errorCode == ERROR_RETRY_LIMIT_EXCEEDED -> UploadError.RetryLimitExceeded

        errorCode == ERROR_QUOTA_EXCEEDED -> UploadError.QuotaExceeded

        errorCode == ERROR_NOT_AUTHENTICATED || errorCode == ERROR_NOT_AUTHORIZED ->
            UploadError.Unauthorized(consentRevoked = false)

        httpCode == PRECONDITION_FAILED -> UploadError.ObjectAlreadyExists

        httpCode == PAYLOAD_TOO_LARGE -> UploadError.TooLarge

        httpCode >= HTTP_ERRORS -> UploadError.Http(httpCode)

        networkCause -> UploadError.Network

        else -> UploadError.Other
    }
}
