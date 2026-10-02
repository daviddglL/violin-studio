package com.violinstudio.domain.feature.consent.usecase

import com.violinstudio.domain.common.RetryBackoff
import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.FakeConsentRepository
import com.violinstudio.domain.feature.auth.failure.AuthFailure
import com.violinstudio.domain.feature.auth.model.SessionClaims
import com.violinstudio.domain.feature.consent.failure.ConsentFailure
import com.violinstudio.domain.feature.profile.model.Role
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AcceptPolicyUseCaseTest {
    private val consent = FakeConsentRepository()
    private val auth = FakeAuthRepository()
    private val useCase = AcceptPolicyUseCase(consent, auth, RetryBackoff())

    @Test
    fun `registra el consentimiento y despues fuerza el refresco de claims`() = runTest {
        assertEquals(Result.success(Unit), useCase(2))
        assertEquals(listOf("recordConsent:2"), consent.calls)
        assertEquals(listOf("claims:true"), auth.calls)
    }

    @Test
    fun `si el callable falla no refresca y propaga PolicyOutdated`() = runTest {
        consent.recordResult = Result.failure(ConsentFailure.PolicyOutdated(3))
        val failure = useCase(2).exceptionOrNull()
        assertTrue(failure is ConsentFailure.PolicyOutdated)
        assertEquals(3, (failure as ConsentFailure.PolicyOutdated).currentVersion)
        assertTrue(auth.calls.isEmpty())
    }

    @Test
    fun `GuardianRequired se propaga`() = runTest {
        consent.recordResult = Result.failure(ConsentFailure.GuardianRequired)
        assertEquals(ConsentFailure.GuardianRequired, useCase(2).exceptionOrNull())
    }

    @Test
    fun `reintenta el refresco y si no lo consigue devuelve fallo`() = runTest {
        auth.claimsResults = mutableListOf(Result.failure(AuthFailure.Network))
        assertEquals(ConsentFailure.Network, useCase(2).exceptionOrNull())
        assertEquals(3, auth.calls.count { it == "claims:true" })
    }

    @Test
    fun `el refresco que acaba bien tras un fallo es exito`() = runTest {
        auth.claimsResults = mutableListOf(
            Result.failure(AuthFailure.Network),
            Result.success(SessionClaims(Role.INDEPENDENT, true))
        )
        assertEquals(Result.success(Unit), useCase(2))
    }

    @Test
    fun `consentOk aun falso tras el refresco cuenta como fallo y se reintenta`() = runTest {
        auth.claimsResults = mutableListOf(
            Result.success(SessionClaims(Role.INDEPENDENT, false)),
            Result.success(SessionClaims(Role.INDEPENDENT, true))
        )
        assertEquals(Result.success(Unit), useCase(2))
        assertEquals(2, auth.calls.count { it == "claims:true" })
    }

    @Test
    fun `consentOk siempre falso agota los intentos y no avanza`() = runTest {
        auth.claimsResults = mutableListOf(Result.success(SessionClaims(Role.INDEPENDENT, false)))
        val failure = useCase(2).exceptionOrNull()
        assertTrue(failure is ConsentFailure.Unknown)
        assertEquals(3, auth.calls.count { it == "claims:true" })
    }

    @Test
    fun `espera entre intentos en tiempo virtual`() = runTest {
        auth.claimsResults = mutableListOf(Result.failure(AuthFailure.Network))
        useCase(2)
        assertEquals(3_000L, testScheduler.currentTime)
    }

    @Test
    fun `TooManyRequests en el refresco se informa como Network`() = runTest {
        auth.claimsResults = mutableListOf(Result.failure(AuthFailure.TooManyRequests))
        assertEquals(ConsentFailure.Network, useCase(2).exceptionOrNull())
    }
}
