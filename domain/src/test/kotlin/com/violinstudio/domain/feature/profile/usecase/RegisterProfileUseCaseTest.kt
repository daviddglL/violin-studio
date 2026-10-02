package com.violinstudio.domain.feature.profile.usecase

import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.FakeProfileRepository
import com.violinstudio.domain.feature.auth.failure.AuthFailure
import com.violinstudio.domain.feature.auth.model.SessionClaims
import com.violinstudio.domain.feature.profile.failure.ProfileFailure
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.profile.model.ProfileRegistration
import com.violinstudio.domain.feature.profile.model.Role
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RegisterProfileUseCaseTest {
    private val auth = FakeAuthRepository()
    private val repo = FakeProfileRepository()
    private val registration =
        ProfileRegistration.create(LocalDate.of(2000, 1, 1), "Ana", Instrument.VIOLIN, "es").getOrThrow()
    private val useCase = RegisterProfileUseCase(repo, auth)

    @Test
    fun `registra y refresca los claims tras el exito`() = runTest {
        assertEquals(Result.success(Unit), useCase(registration))
        assertEquals(registration, repo.lastRegistration)
        assertEquals(listOf("claims:true"), auth.calls)
    }

    @Test
    fun `si el registro falla no refresca claims y propaga el fallo`() = runTest {
        repo.registerResult = Result.failure(ProfileFailure.UnderageNotAllowed)
        assertEquals(ProfileFailure.UnderageNotAllowed, useCase(registration).exceptionOrNull())
        assertTrue(auth.calls.isEmpty())
    }

    @Test
    fun `reintenta el refresco si falla y acaba bien`() = runTest {
        auth.claimsResults = mutableListOf(
            Result.failure(AuthFailure.Network),
            Result.success(SessionClaims(Role.INDEPENDENT, false))
        )
        assertEquals(Result.success(Unit), useCase(registration))
        assertEquals(listOf("claims:true", "claims:true"), auth.calls)
    }

    @Test
    fun `si el refresco sigue fallando no avanza y devuelve fallo`() = runTest {
        auth.claimsResults = mutableListOf(Result.failure(AuthFailure.Network))
        val result = useCase(registration)
        assertEquals(ProfileFailure.Network, result.exceptionOrNull())
        assertEquals(3, auth.calls.count { it == "claims:true" })
    }

    @Test
    fun `un fallo de refresco desconocido se envuelve en Unknown con su causa`() = runTest {
        val cause = AuthFailure.ProviderUnavailable
        auth.claimsResults = mutableListOf(Result.failure(cause))
        val failure = useCase(registration).exceptionOrNull()
        assertTrue(failure is ProfileFailure.Unknown)
        assertEquals(cause, failure?.cause)
    }
}
