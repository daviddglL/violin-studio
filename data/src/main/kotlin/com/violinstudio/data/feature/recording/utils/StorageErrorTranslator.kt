package com.violinstudio.data.feature.recording.utils

import com.google.firebase.storage.StorageException
import com.violinstudio.domain.feature.recording.model.UploadError
import java.io.IOException

/** Traduce las excepciones del SDK de Storage a [UploadError]; `domain` no conoce Firebase. */
object StorageErrorTranslator {
    private val HTTP_ERRORS = 400..599

    fun translate(error: Throwable): UploadError = when (error) {
        is StorageException -> translate(error.errorCode, error.httpResultCode, error.cause)
        is IOException -> UploadError.Network
        else -> UploadError.Other
    }

    fun translate(errorCode: Int, httpCode: Int, cause: Throwable?): UploadError = when {
        errorCode == StorageException.ERROR_RETRY_LIMIT_EXCEEDED -> UploadError.RetryLimitExceeded
        errorCode == StorageException.ERROR_QUOTA_EXCEEDED -> UploadError.QuotaExceeded
        errorCode == StorageException.ERROR_NOT_AUTHENTICATED -> UploadError.Unauthenticated
        errorCode == StorageException.ERROR_INVALID_CHECKSUM -> UploadError.Network
        errorCode == StorageException.ERROR_NOT_AUTHORIZED -> UploadError.Unauthorized(consentRevoked = false)
        httpCode in HTTP_ERRORS -> UploadError.Http(httpCode)
        cause is IOException -> UploadError.Network
        else -> UploadError.Other
    }

    fun isCanceled(error: Throwable): Boolean = error is StorageException && isCanceled(error.errorCode)

    fun isCanceled(errorCode: Int): Boolean = errorCode == StorageException.ERROR_CANCELED
}
