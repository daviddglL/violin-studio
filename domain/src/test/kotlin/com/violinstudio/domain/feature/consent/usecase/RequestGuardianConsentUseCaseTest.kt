package com.violinstudio.domain.feature.consent.usecase

import com.violinstudio.domain.feature.FakeConsentRepository
import com.violinstudio.domain.feature.consent.failure.ConsentFailure
import com.violinstudio.domain.feature.consent.model.GuardianRequestReceipt
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RequestGuardianConsentUseCaseTest {
    private val consent = FakeConsentRepository()
    private val useCase = RequestGuardianConsentUseCase(consent)

    @Test
    fun `envia la solicitud con el email recortado y devuelve el acuse`() = runTest {
        assertEquals(Result.success(GuardianRequestReceipt("t***@x.com")), useCase(" tutor@x.com "))
        assertEquals(listOf("requestGuardianConsent:tutor@x.com"), consent.calls)
    }

    @Test
    fun `un email con formato invalido no llama al servidor`() = runTest {
        assertEquals(ConsentFailure.GuardianEmailInvalid, useCase("tutor").exceptionOrNull())
        assertTrue(consent.calls.isEmpty())
    }

    @Test
    fun `RateLimited conserva retryAfterSeconds`() = runTest {
        consent.guardianResult = Result.failure(ConsentFailure.RateLimited(60))
        val failure = useCase("tutor@x.com").exceptionOrNull()
        assertTrue(failure is ConsentFailure.RateLimited)
        assertEquals(60L, (failure as ConsentFailure.RateLimited).retryAfterSeconds)
    }

    @Test
    fun `NotMinor se propaga`() = runTest {
        consent.guardianResult = Result.failure(ConsentFailure.NotMinor)
        assertEquals(ConsentFailure.NotMinor, useCase("tutor@x.com").exceptionOrNull())
    }
}
