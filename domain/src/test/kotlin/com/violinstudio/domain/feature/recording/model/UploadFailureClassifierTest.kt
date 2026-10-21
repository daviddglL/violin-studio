package com.violinstudio.domain.feature.recording.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** REQ-REC-04 / REQ-REC-14 / R1: transitorio reintenta, permanente falla, revocacion borra el fichero. */
class UploadFailureClassifierTest {
    private fun classify(error: UploadError) = UploadFailureClassifier.classify(error)

    @Test
    fun `red, 5xx, limite de reintentos del SDK, timeout y 429 reintentan`() {
        listOf(
            UploadError.Network,
            UploadError.RetryLimitExceeded,
            UploadError.Http(500),
            UploadError.Http(503),
            UploadError.Http(408),
            UploadError.Http(429)
        ).forEach { assertEquals(UploadDecision.Retry, classify(it), it.toString()) }
    }

    @Test
    fun `no autorizado sin revocacion falla por permiso y conserva el fichero`() {
        val unauthorized = UploadError.Unauthorized(consentRevoked = false)
        assertEquals(UploadDecision.Fail(FailReason.PERMISSION), classify(unauthorized))
        assertEquals(UploadDecision.Fail(FailReason.PERMISSION), classify(UploadError.Http(403)))
    }

    @Test
    fun `no autorizado por consentimiento revocado o epoca distinta borra el fichero (R1)`() {
        assertEquals(UploadDecision.ConsentRevoked, classify(UploadError.Unauthorized(consentRevoked = true)))
    }

    @Test
    fun `objeto ya existente o 412 cuentan como subido`() {
        assertEquals(UploadDecision.AlreadyUploaded, classify(UploadError.ObjectAlreadyExists))
        assertEquals(UploadDecision.AlreadyUploaded, classify(UploadError.Http(412)))
    }

    @Test
    fun `cuota y tamano fallan con su motivo, el resto de 4xx es desconocido`() {
        assertEquals(UploadDecision.Fail(FailReason.QUOTA), classify(UploadError.QuotaExceeded))
        assertEquals(UploadDecision.Fail(FailReason.SIZE), classify(UploadError.TooLarge))
        assertEquals(UploadDecision.Fail(FailReason.UNKNOWN), classify(UploadError.Http(400)))
        assertEquals(UploadDecision.Fail(FailReason.UNKNOWN), classify(UploadError.Http(404)))
        assertEquals(UploadDecision.Fail(FailReason.SIZE), classify(UploadError.Http(413)))
        assertEquals(UploadDecision.Fail(FailReason.PERMISSION), classify(UploadError.Http(401)))
        assertEquals(UploadDecision.Fail(FailReason.UNKNOWN), classify(UploadError.Other))
    }
}
