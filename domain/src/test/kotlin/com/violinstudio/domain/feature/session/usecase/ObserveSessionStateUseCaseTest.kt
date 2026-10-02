package com.violinstudio.domain.feature.session.usecase

import app.cash.turbine.test
import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.FakeConsentRepository
import com.violinstudio.domain.feature.FakeProfileRepository
import com.violinstudio.domain.feature.auth.model.SessionClaims
import com.violinstudio.domain.feature.config
import com.violinstudio.domain.feature.consent.failure.ConsentFailure
import com.violinstudio.domain.feature.passwordUser
import com.violinstudio.domain.feature.profile.model.ConsentStatus
import com.violinstudio.domain.feature.profile.model.Role
import com.violinstudio.domain.feature.session.SessionState
import com.violinstudio.domain.feature.session.SessionStateResolver
import com.violinstudio.domain.feature.userProfile
import com.violinstudio.domain.feature.verifiedUser
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ObserveSessionStateUseCaseTest {
    private val auth = FakeAuthRepository()
    private val profile = FakeProfileRepository()
    private val consent = FakeConsentRepository()
    private val useCase = ObserveSessionStateUseCase(auth, profile, consent, SessionStateResolver())

    private fun claims(consentOk: Boolean) = Result.success(SessionClaims(Role.INDEPENDENT, consentOk))

    @Test
    fun `emite Loading al empezar y LoggedOut sin sesion`() = runTest {
        useCase().test {
            assertEquals(SessionState.Loading, awaitItem())
            assertEquals(SessionState.LoggedOut, awaitItem())
            expectNoEvents()
        }
        assertTrue(consent.calls.isEmpty())
    }

    @Test
    fun `email sin verificar no consulta config ni perfil`() = runTest {
        auth.user.value = passwordUser
        useCase().test {
            assertEquals(SessionState.Loading, awaitItem())
            assertEquals(SessionState.EmailUnverified("a@b.com"), awaitItem())
        }
        assertTrue(consent.calls.isEmpty())
        assertTrue(profile.calls.isEmpty())
    }

    @Test
    fun `verificado sin perfil es NeedsProfile y con perfil concedido es Ready`() = runTest {
        auth.user.value = verifiedUser
        useCase().test {
            assertEquals(SessionState.Loading, awaitItem())
            assertEquals(SessionState.NeedsProfile, awaitItem())
            profile.profile.value = userProfile()
            assertEquals(SessionState.Ready(userProfile()), awaitItem())
        }
        // claims ya en regla: nunca se fuerza el refresco
        assertTrue("claims:true" !in auth.calls)
    }

    @Test
    fun `granted con claim falso fuerza un unico refresco antes de Ready`() = runTest {
        auth.claimsResults = mutableListOf(claims(false), claims(true))
        profile.profile.value = userProfile()
        auth.user.value = verifiedUser
        useCase().test {
            assertEquals(SessionState.Loading, awaitItem())
            assertEquals(SessionState.Ready(userProfile()), awaitItem())
            assertEquals(1, auth.calls.count { it == "claims:true" })
        }
    }

    @Test
    fun `el refresco forzado no se repite aunque el perfil reemita con el claim aun falso`() = runTest {
        auth.claimsResults = mutableListOf(claims(false))
        profile.profile.value = userProfile()
        auth.user.value = verifiedUser
        useCase().test {
            assertEquals(SessionState.Loading, awaitItem())
            assertEquals(SessionState.Ready(userProfile()), awaitItem())
            val renamed = userProfile().copy(displayName = "Berta")
            profile.profile.value = renamed
            assertEquals(SessionState.Ready(renamed), awaitItem())
            assertEquals(1, auth.calls.count { it == "claims:true" })
        }
    }

    @Test
    fun `si el refresco falla igualmente emite Ready porque el perfil manda`() = runTest {
        auth.claimsResults = mutableListOf(claims(false), Result.failure(ConsentFailure.Network))
        profile.profile.value = userProfile()
        auth.user.value = verifiedUser
        useCase().test {
            assertEquals(SessionState.Loading, awaitItem())
            assertEquals(SessionState.Ready(userProfile()), awaitItem())
        }
    }

    @Test
    fun `bump de version en caliente pasa de Ready a ConsentPending`() = runTest {
        profile.profile.value = userProfile()
        auth.user.value = verifiedUser
        useCase().test {
            assertEquals(SessionState.Loading, awaitItem())
            assertEquals(SessionState.Ready(userProfile()), awaitItem())
            profile.profile.value = userProfile(status = ConsentStatus.PENDING)
            assertEquals(SessionState.ConsentPending(config, isMinor = false), awaitItem())
        }
    }

    @Test
    fun `granted con version antigua es ConsentPending`() = runTest {
        profile.profile.value = userProfile(policyVersion = 1)
        auth.user.value = verifiedUser
        useCase().test {
            assertEquals(SessionState.Loading, awaitItem())
            assertEquals(SessionState.ConsentPending(config, isMinor = false), awaitItem())
        }
    }

    @Test
    fun `revocacion en caliente de un menor es ConsentPending en modo menor`() = runTest {
        profile.profile.value = userProfile(isMinor = true)
        auth.user.value = verifiedUser
        useCase().test {
            assertEquals(SessionState.Loading, awaitItem())
            assertEquals(SessionState.Ready(userProfile(isMinor = true)), awaitItem())
            profile.profile.value = userProfile(status = ConsentStatus.REVOKED, isMinor = true)
            assertEquals(SessionState.ConsentPending(config, isMinor = true), awaitItem())
        }
    }

    @Test
    fun `cerrar sesion en caliente emite LoggedOut`() = runTest {
        profile.profile.value = userProfile()
        auth.user.value = verifiedUser
        useCase().test {
            assertEquals(SessionState.Loading, awaitItem())
            assertEquals(SessionState.Ready(userProfile()), awaitItem())
            auth.user.value = null
            assertEquals(SessionState.LoggedOut, awaitItem())
        }
    }

    @Test
    fun `borrado en curso es Loading`() = runTest {
        profile.profile.value = userProfile(deletion = true)
        auth.user.value = verifiedUser
        useCase().test {
            assertEquals(SessionState.Loading, awaitItem())
            expectNoEvents()
        }
    }

    @Test
    fun `si identityConfig falla el flujo termina con ese fallo`() = runTest {
        consent.configResult = Result.failure(ConsentFailure.Network)
        auth.user.value = verifiedUser
        useCase().test {
            assertEquals(SessionState.Loading, awaitItem())
            assertEquals(ConsentFailure.Network, awaitError())
        }
    }
}
