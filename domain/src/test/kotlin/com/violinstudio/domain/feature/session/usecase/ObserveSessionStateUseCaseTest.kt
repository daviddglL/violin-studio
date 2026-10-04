package com.violinstudio.domain.feature.session.usecase

import app.cash.turbine.test
import com.violinstudio.domain.common.RetryBackoff
import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.FakeConsentRepository
import com.violinstudio.domain.feature.FakeProfileRepository
import com.violinstudio.domain.feature.auth.failure.AuthFailure
import com.violinstudio.domain.feature.auth.model.SessionClaims
import com.violinstudio.domain.feature.config
import com.violinstudio.domain.feature.consent.PendingGuardianEmail
import com.violinstudio.domain.feature.passwordUser
import com.violinstudio.domain.feature.profile.failure.ProfileFailure
import com.violinstudio.domain.feature.profile.model.ConsentStatus
import com.violinstudio.domain.feature.profile.model.Role
import com.violinstudio.domain.feature.session.ConsentReason
import com.violinstudio.domain.feature.session.SessionRefreshTrigger
import com.violinstudio.domain.feature.session.SessionState
import com.violinstudio.domain.feature.session.SessionStateResolver
import com.violinstudio.domain.feature.userProfile
import com.violinstudio.domain.feature.verifiedUser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ObserveSessionStateUseCaseTest {
    private val auth = FakeAuthRepository()
    private val profile = FakeProfileRepository()
    private val consent = FakeConsentRepository()
    private val trigger = SessionRefreshTrigger()
    private val pending = PendingGuardianEmail()
    private val useCase =
        ObserveSessionStateUseCase(auth, profile, consent, SessionStateResolver(), RetryBackoff(), trigger, pending)

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
        auth.user = passwordUser
        useCase().test {
            assertEquals(SessionState.Loading, awaitItem())
            assertEquals(SessionState.EmailUnverified("a@b.com"), awaitItem())
        }
        assertTrue(consent.calls.isEmpty())
        assertTrue(profile.calls.isEmpty())
    }

    @Test
    fun `verificado sin perfil es NeedsProfile y con perfil concedido es Ready`() = runTest {
        auth.user = verifiedUser
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
        auth.user = verifiedUser
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
        auth.user = verifiedUser
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
        auth.claimsResults = mutableListOf(claims(false), Result.failure(AuthFailure.Network))
        profile.profile.value = userProfile()
        auth.user = verifiedUser
        useCase().test {
            assertEquals(SessionState.Loading, awaitItem())
            assertEquals(SessionState.Ready(userProfile()), awaitItem())
        }
    }

    @Test
    fun `bump de version en caliente pasa de Ready a ConsentPending`() = runTest {
        profile.profile.value = userProfile()
        auth.user = verifiedUser
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
        auth.user = verifiedUser
        useCase().test {
            assertEquals(SessionState.Loading, awaitItem())
            assertEquals(
                SessionState.ConsentPending(config, isMinor = false, reason = ConsentReason.POLICY_UPDATED),
                awaitItem()
            )
        }
    }

    @Test
    fun `revocacion en caliente de un menor es ConsentPending en modo menor`() = runTest {
        profile.profile.value = userProfile(isMinor = true)
        auth.user = verifiedUser
        useCase().test {
            assertEquals(SessionState.Loading, awaitItem())
            assertEquals(SessionState.Ready(userProfile(isMinor = true)), awaitItem())
            profile.profile.value = userProfile(status = ConsentStatus.REVOKED, isMinor = true)
            assertEquals(
                SessionState.ConsentPending(config, isMinor = true, reason = ConsentReason.REVOKED),
                awaitItem()
            )
        }
    }

    @Test
    fun `cerrar sesion en caliente emite LoggedOut`() = runTest {
        profile.profile.value = userProfile()
        auth.user = verifiedUser
        useCase().test {
            assertEquals(SessionState.Loading, awaitItem())
            assertEquals(SessionState.Ready(userProfile()), awaitItem())
            auth.user = null
            assertEquals(SessionState.LoggedOut, awaitItem())
        }
    }

    @Test
    fun `marca de borrado en curso tras Ready vuelve a Loading emitido por el resolver`() = runTest {
        profile.profile.value = userProfile()
        auth.user = verifiedUser
        useCase().test {
            assertEquals(SessionState.Loading, awaitItem())
            assertEquals(SessionState.Ready(userProfile()), awaitItem())
            profile.profile.value = userProfile(deletion = true)
            assertEquals(SessionState.Loading, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `perfil que pasa a null tras Ready es NeedsProfile`() = runTest {
        profile.profile.value = userProfile()
        auth.user = verifiedUser
        useCase().test {
            assertEquals(SessionState.Loading, awaitItem())
            assertEquals(SessionState.Ready(userProfile()), awaitItem())
            profile.profile.value = null
            assertEquals(SessionState.NeedsProfile, awaitItem())
            expectNoEvents()
        }
    }

    @Test
    fun `reconceder tras revocar vuelve a forzar el refresco una sola vez mas`() = runTest {
        auth.claimsResults = mutableListOf(claims(false))
        profile.profile.value = userProfile()
        auth.user = verifiedUser
        useCase().test {
            assertEquals(SessionState.Loading, awaitItem())
            assertEquals(SessionState.Ready(userProfile()), awaitItem())
            assertEquals(1, auth.calls.count { it == "claims:true" })
            profile.profile.value = userProfile(status = ConsentStatus.REVOKED)
            assertEquals(
                SessionState.ConsentPending(config, isMinor = false, reason = ConsentReason.REVOKED),
                awaitItem()
            )
            profile.profile.value = userProfile()
            assertEquals(SessionState.Ready(userProfile()), awaitItem())
            assertEquals(2, auth.calls.count { it == "claims:true" })
            expectNoEvents()
        }
    }

    @Test
    fun `una reemision igual de authUser tras el refresco no reinicia ni repite el refresco`() = runTest {
        auth.claimsResults = mutableListOf(claims(false))
        profile.profile.value = userProfile()
        auth.user = verifiedUser
        useCase().test {
            assertEquals(SessionState.Loading, awaitItem())
            assertEquals(SessionState.Ready(userProfile()), awaitItem())
            auth.user = verifiedUser.copy()
            expectNoEvents()
        }
        assertEquals(1, auth.calls.count { it == "claims:true" })
        assertEquals(1, consent.calls.count { it == "identityConfig" })
    }

    @Test
    fun `un refresco pedido vuelve a leer identityConfig y la sesion se resuelve contra la version nueva`() = runTest {
        val v1 = config.copy(policyVersion = 1)
        val v2 = config.copy(policyVersion = 2)
        consent.configQueue += listOf(Result.success(v1), Result.success(v2))
        profile.profile.value = userProfile(status = ConsentStatus.PENDING, policyVersion = null)
        auth.user = verifiedUser
        useCase().test {
            assertEquals(SessionState.Loading, awaitItem())
            assertEquals(SessionState.ConsentPending(v1, isMinor = false), awaitItem())
            // El usuario acepta la v2 tras PolicyOutdated: el perfil ya dice granted/v2 pero la config cacheada es v1.
            profile.profile.value = userProfile(policyVersion = 2)
            assertEquals(
                SessionState.ConsentPending(v1, isMinor = false, reason = ConsentReason.POLICY_UPDATED),
                awaitItem()
            )
            trigger.requestRefresh()
            assertEquals(SessionState.Ready(userProfile(policyVersion = 2)), awaitItem())
            assertEquals(2, consent.calls.count { it == "identityConfig" })
        }
    }

    @Test
    fun `un refresco no repite el refresco forzado de claims ni emite Loading`() = runTest {
        auth.claimsResults = mutableListOf(claims(false))
        profile.profile.value = userProfile()
        auth.user = verifiedUser
        useCase().test {
            assertEquals(SessionState.Loading, awaitItem())
            assertEquals(SessionState.Ready(userProfile()), awaitItem())
            trigger.requestRefresh()
            expectNoEvents()
        }
        assertEquals(1, auth.calls.count { it == "claims:true" })
        assertEquals(2, consent.calls.count { it == "identityConfig" })
    }

    private fun TestScope.collectStates(): List<SessionState> {
        val states = mutableListOf<SessionState>()
        backgroundScope.launch { useCase().toList(states) }
        runCurrent()
        return states
    }

    private fun TestScope.passTime(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    @Test
    fun `si identityConfig falla emite Unavailable y reintenta con espera exponencial sin terminar`() = runTest {
        consent.configQueue += listOf(Result.failure(AuthFailure.Network), Result.failure(AuthFailure.Network))
        profile.profile.value = userProfile()
        auth.user = verifiedUser
        val states = collectStates()
        assertEquals(listOf(SessionState.Loading, SessionState.Unavailable), states)
        assertEquals(1, consent.calls.size)
        passTime(999)
        assertEquals(1, consent.calls.size)
        passTime(1)
        assertEquals(2, consent.calls.size)
        passTime(1_999)
        assertEquals(2, consent.calls.size)
        passTime(1)
        assertEquals(3, consent.calls.size)
        assertEquals(
            listOf(SessionState.Loading, SessionState.Unavailable, SessionState.Ready(userProfile())),
            states
        )
    }

    @Test
    fun `cerrar sesion mientras esta Unavailable cancela los reintentos`() = runTest {
        consent.configResult = Result.failure(AuthFailure.Network)
        auth.user = verifiedUser
        val states = collectStates()
        auth.user = null
        runCurrent()
        passTime(60_000)
        assertEquals(listOf(SessionState.Loading, SessionState.Unavailable, SessionState.LoggedOut), states)
        assertEquals(1, consent.calls.size)
    }

    @Test
    fun `un error del listener de perfil emite Unavailable y reengancha con espera`() = runTest {
        profile.observeFailures += ProfileFailure.Network
        profile.profile.value = userProfile()
        auth.user = verifiedUser
        val states = collectStates()
        assertEquals(listOf(SessionState.Loading, SessionState.Unavailable), states)
        passTime(1_000)
        assertEquals(listOf(SessionState.Loading, SessionState.Unavailable, SessionState.Ready(userProfile())), states)
        assertEquals(2, profile.calls.size)
    }

    @Test
    fun `permission-denied o no encontrado en el listener se trata como perfil nulo`() = runTest {
        profile.observeFailures += ProfileFailure.NoProfile
        profile.profile.value = userProfile()
        auth.user = verifiedUser
        val states = collectStates()
        assertEquals(listOf(SessionState.Loading, SessionState.NeedsProfile), states)
        passTime(1_000)
        assertEquals(listOf(SessionState.Loading, SessionState.NeedsProfile, SessionState.Ready(userProfile())), states)
    }

    @Test
    fun `la confirmacion del tutor lleva de ParentalPending a Ready con un unico refresco de claims`() = runTest {
        auth.claimsResults = mutableListOf(claims(false), claims(true))
        profile.profile.value = userProfile(status = ConsentStatus.PARENTAL_PENDING, policyVersion = null)
        auth.user = verifiedUser
        useCase().test {
            assertEquals(SessionState.Loading, awaitItem())
            assertEquals(SessionState.ParentalPending(null, 0), awaitItem())
            assertEquals(0, auth.calls.count { it == "claims:true" })
            profile.profile.value = userProfile()
            assertEquals(SessionState.Ready(userProfile()), awaitItem())
            assertEquals(1, auth.calls.count { it == "claims:true" })
        }
    }

    @Test
    fun `cerrar sesion olvida el email del tutor recordado`() = runTest {
        pending.remember("tutor@x.com")
        profile.profile.value = userProfile(status = ConsentStatus.PARENTAL_PENDING, policyVersion = null)
        auth.user = verifiedUser
        useCase().test {
            assertEquals(SessionState.Loading, awaitItem())
            assertEquals(SessionState.ParentalPending(null, 0), awaitItem())
            assertEquals("tutor@x.com", pending.email)
            auth.user = null
            assertEquals(SessionState.LoggedOut, awaitItem())
            assertEquals(null, pending.email)
        }
    }
}
