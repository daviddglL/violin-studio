package com.violinstudio.domain.feature.session

import com.violinstudio.domain.feature.auth.model.AuthProvider
import com.violinstudio.domain.feature.auth.model.AuthUser
import com.violinstudio.domain.feature.consent.model.IdentityConfig
import com.violinstudio.domain.feature.profile.model.ConsentStatus
import com.violinstudio.domain.feature.profile.model.GuardianSummary
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.profile.model.Role
import com.violinstudio.domain.feature.profile.model.UserProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SessionStateResolverTest {
    private val config = IdentityConfig(policyVersion = 2, policyUrl = "https://x/privacy", digitalConsentAge = 14, guardianFlowEnabled = true)
    private val password = AuthUser("u1", "a@b.com", emailVerified = true, providers = setOf(AuthProvider.PASSWORD))
    private val resolver = SessionStateResolver()

    private fun profile(
        status: ConsentStatus = ConsentStatus.GRANTED,
        policyVersion: Int? = 2,
        isMinor: Boolean = false,
        guardian: GuardianSummary? = null,
        deletion: Boolean = false
    ) = UserProfile("u1", "Ana", Instrument.VIOLIN, "es", Role.INDEPENDENT, isMinor, status, policyVersion, guardian, deletion)

    private fun resolve(user: AuthUser? = password, profile: UserProfile? = profile()) = resolver(user, profile, config)

    @Test
    fun `sin sesion es LoggedOut aunque haya perfil`() {
        assertEquals(SessionState.LoggedOut, resolve(user = null))
    }

    @Test
    fun `email y contrasena sin verificar es EmailUnverified`() {
        val user = password.copy(emailVerified = false)
        assertEquals(SessionState.EmailUnverified("a@b.com"), resolve(user = user, profile = null))
    }

    @Test
    fun `Google nunca es EmailUnverified`() {
        val google = AuthUser("u1", "a@b.com", emailVerified = false, providers = setOf(AuthProvider.GOOGLE))
        assertEquals(SessionState.NeedsProfile, resolve(user = google, profile = null))
    }

    @Test
    fun `verificado sin perfil es NeedsProfile`() {
        assertEquals(SessionState.NeedsProfile, resolve(profile = null))
    }

    @Test
    fun `pending adulto y menor van a ConsentPending con su modo`() {
        assertEquals(
            SessionState.ConsentPending(config, isMinor = false),
            resolve(profile = profile(ConsentStatus.PENDING, null))
        )
        assertEquals(
            SessionState.ConsentPending(config, isMinor = true),
            resolve(profile = profile(ConsentStatus.PENDING, null, isMinor = true))
        )
    }

    @Test
    fun `revoked adulto y menor van a ConsentPending`() {
        assertEquals(SessionState.ConsentPending(config, false), resolve(profile = profile(ConsentStatus.REVOKED, 2)))
        assertEquals(SessionState.ConsentPending(config, true), resolve(profile = profile(ConsentStatus.REVOKED, 2, isMinor = true)))
    }

    @Test
    fun `parental_pending muestra la espera con el email enmascarado`() {
        val guardian = GuardianSummary(emailMasked = "p***@g***.com", sends = 2)
        assertEquals(
            SessionState.ParentalPending("p***@g***.com", 2),
            resolve(profile = profile(ConsentStatus.PARENTAL_PENDING, null, isMinor = true, guardian = guardian))
        )
        assertEquals(
            SessionState.ParentalPending(null, 0),
            resolve(profile = profile(ConsentStatus.PARENTAL_PENDING, null, isMinor = true))
        )
    }

    @Test
    fun `granted vigente es Ready y una version superior sigue vigente`() {
        val current = profile()
        assertEquals(SessionState.Ready(current), resolve(profile = current))
        val newer = profile(policyVersion = 3)
        assertEquals(SessionState.Ready(newer), resolve(profile = newer))
    }

    @Test
    fun `granted con version anterior o sin version vuelve a ConsentPending`() {
        assertEquals(SessionState.ConsentPending(config, false), resolve(profile = profile(policyVersion = 1)))
        assertEquals(SessionState.ConsentPending(config, true), resolve(profile = profile(policyVersion = 1, isMinor = true)))
        assertEquals(SessionState.ConsentPending(config, false), resolve(profile = profile(policyVersion = null)))
    }

    @Test
    fun `borrado en curso es Loading en cualquier estado de consentimiento`() {
        ConsentStatus.entries.forEach {
            assertEquals(SessionState.Loading, resolve(profile = profile(it, 2, deletion = true)))
        }
    }

    @Test
    fun `el servidor decide el modo menor y no la edad calculada en cliente`() {
        // `isMinor` del perfil prevalece: el resolver no recibe fecha de nacimiento ni AgeGate.
        assertEquals(
            SessionState.ConsentPending(config, isMinor = true),
            resolve(profile = profile(ConsentStatus.PENDING, null, isMinor = true))
        )
    }
}
