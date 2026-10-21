package com.violinstudio.data.feature.recording.utils

import com.google.firebase.storage.StorageException
import com.violinstudio.domain.feature.recording.model.UploadError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StorageErrorMapperTest {
    private fun map(code: Int = 0, http: Int = 0, network: Boolean = false) =
        StorageErrorMapper.map(code, http, network)

    @Test
    fun `los codigos copiados coinciden con los del SDK`() {
        assertEquals(StorageException.ERROR_QUOTA_EXCEEDED, StorageErrorMapper.ERROR_QUOTA_EXCEEDED)
        assertEquals(StorageException.ERROR_NOT_AUTHENTICATED, StorageErrorMapper.ERROR_NOT_AUTHENTICATED)
        assertEquals(StorageException.ERROR_NOT_AUTHORIZED, StorageErrorMapper.ERROR_NOT_AUTHORIZED)
        assertEquals(StorageException.ERROR_RETRY_LIMIT_EXCEEDED, StorageErrorMapper.ERROR_RETRY_LIMIT_EXCEEDED)
    }

    @Test
    fun `codigos del SDK`() {
        assertEquals(UploadError.RetryLimitExceeded, map(StorageException.ERROR_RETRY_LIMIT_EXCEEDED, network = true))
        assertEquals(UploadError.QuotaExceeded, map(StorageException.ERROR_QUOTA_EXCEEDED, 402))
        assertEquals(UploadError.Unauthorized(false), map(StorageException.ERROR_NOT_AUTHORIZED, 403))
        assertEquals(UploadError.Unauthorized(false), map(StorageException.ERROR_NOT_AUTHENTICATED, 401))
    }

    @Test
    fun `codigos HTTP, red y resto`() {
        assertEquals(UploadError.ObjectAlreadyExists, map(StorageException.ERROR_UNKNOWN, 412))
        assertEquals(UploadError.TooLarge, map(StorageException.ERROR_UNKNOWN, 413))
        assertEquals(UploadError.Http(503), map(StorageException.ERROR_UNKNOWN, 503))
        assertEquals(UploadError.Http(410), map(StorageException.ERROR_UNKNOWN, 410))
        assertEquals(UploadError.Network, map(StorageException.ERROR_UNKNOWN, network = true))
        assertEquals(UploadError.Other, map(StorageException.ERROR_UNKNOWN, 200))
        assertEquals(UploadError.Other, map(StorageException.ERROR_UNKNOWN))
    }
}
