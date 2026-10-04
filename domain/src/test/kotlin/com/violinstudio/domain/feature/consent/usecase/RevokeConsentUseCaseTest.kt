package com.violinstudio.domain.feature.consent.usecase

import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.FakeConsentRepository
import com.violinstudio.domain.feature.auth.failure.AuthFailure
import com.violinstudio.domain.feature.consent.failure.ConsentFailure
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RevokeConsentUseCaseTest {
    private val consent = FakeConsentRepository()
    private val auth = FakeAuthRepository()
    private val useCase = RevokeConsentUseCase(consent, auth)

    @Test
    fun `revoca y refresca los claims`() = runTest {
        assertEquals(Result.success(Unit), useCase())
        assertEquals(listOf("revokeConsent"), consent.calls)
        assertEquals(listOf("claims:true"), auth.calls)
    }

    @Test
    fun `NoActiveConsent se propaga sin refrescar`() = runTest {
        consent.revokeResult = Result.failure(ConsentFailure.NoActiveConsent)
        assertEquals(ConsentFailure.NoActiveConsent, useCase().exceptionOrNull())
        assertTrue(auth.calls.isEmpty())
    }

    @Test
    fun `un fallo de refresco no invalida la revocacion ya hecha`() = runTest {
        auth.claimsResults = mutableListOf(Result.failure(AuthFailure.Network))
        assertEquals(Result.success(Unit), useCase())
    }
}
