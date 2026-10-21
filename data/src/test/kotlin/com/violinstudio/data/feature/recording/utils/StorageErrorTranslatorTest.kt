package com.violinstudio.data.feature.recording.utils

import com.google.firebase.storage.StorageException
import com.violinstudio.domain.feature.recording.model.UploadError
import java.io.IOException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Traduccion de las excepciones REALES del SDK de Storage a `UploadError` (riesgo de B7a). */
class StorageErrorTranslatorTest {
    private fun real(code: Int, cause: Throwable? = IOException("red")) =
        StorageException.fromExceptionAndHttpCode(cause, code)!!

    @Test
    fun `un 403 y un 401 reales del SDK son Unauthorized sin consentimiento resuelto`() {
        assertEquals(UploadError.Unauthorized(false), StorageErrorTranslator.translate(real(403)))
        assertEquals(UploadError.Unauthenticated, StorageErrorTranslator.translate(real(401)))
    }

    @Test
    fun `los codigos HTTP del servidor se conservan para el clasificador`() {
        assertEquals(UploadError.Http(503), StorageErrorTranslator.translate(real(503)))
        assertEquals(UploadError.Http(412), StorageErrorTranslator.translate(real(412)))
        assertEquals(UploadError.Http(404), StorageErrorTranslator.translate(real(404)))
        assertEquals(UploadError.Http(413), StorageErrorTranslator.translate(real(413)))
    }

    @Test
    fun `los codigos de error del SDK sin HTTP se traducen por su codigo`() {
        assertEquals(
            UploadError.RetryLimitExceeded,
            StorageErrorTranslator.translate(StorageException.ERROR_RETRY_LIMIT_EXCEEDED, 0, null)
        )
        assertEquals(
            UploadError.QuotaExceeded,
            StorageErrorTranslator.translate(StorageException.ERROR_QUOTA_EXCEEDED, 0, null)
        )
        assertEquals(
            UploadError.Unauthorized(false),
            StorageErrorTranslator.translate(StorageException.ERROR_NOT_AUTHORIZED, 0, null)
        )
        assertEquals(
            UploadError.Unauthenticated,
            StorageErrorTranslator.translate(StorageException.ERROR_NOT_AUTHENTICATED, 0, null)
        )
    }

    @Test
    fun `una IOException suelta o con causa de red es Network y lo demas Other`() {
        assertEquals(UploadError.Network, StorageErrorTranslator.translate(IOException("x")))
        assertEquals(
            UploadError.Network,
            StorageErrorTranslator.translate(StorageException.ERROR_UNKNOWN, 0, IOException("x"))
        )
        assertEquals(UploadError.Other, StorageErrorTranslator.translate(IllegalStateException("x")))
        assertEquals(UploadError.Other, StorageErrorTranslator.translate(StorageException.ERROR_UNKNOWN, 0, null))
    }

    @Test
    fun `sin sesion de Auth es Unauthenticated (transitorio) y un checksum invalido es transitorio`() {
        assertEquals(
            UploadError.Unauthenticated,
            StorageErrorTranslator.translate(StorageException.ERROR_NOT_AUTHENTICATED, 401, null)
        )
        assertEquals(
            UploadError.Network,
            StorageErrorTranslator.translate(StorageException.ERROR_INVALID_CHECKSUM, 0, null)
        )
    }

    @Test
    fun `solo ERROR_CANCELED cuenta como cancelacion`() {
        assertFalse(StorageErrorTranslator.isCanceled(real(403)))
        assertFalse(StorageErrorTranslator.isCanceled(IOException()))
        assertTrue(StorageErrorTranslator.isCanceled(StorageException.ERROR_CANCELED))
    }
}
