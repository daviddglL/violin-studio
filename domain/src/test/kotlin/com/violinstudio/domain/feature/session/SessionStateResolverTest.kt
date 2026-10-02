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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class SessionStateResolverTest {
    private val config =
        IdentityConfig(
            policyVersion = 2,
            policyUrl = "https://x/privacy",
            digitalConsentAge = 14,
            guardianFlowEnabled = true
        )
    private val password = AuthUser("u1", "a@b.com", emailVerified = true, providers = setOf(AuthProvider.PASSWORD))
    private val resolver = SessionStateResolver()

    private fun profile(
        status: ConsentStatus = ConsentStatus.GRANTED,
        policyVersion: Int? = 2,
        isMinor: Boolean = false,
        guardian: GuardianSummary? = null,
        deletion: Boolean = false
    ) = UserProfile(
        uid = "u1",
        displayName = "Ana",
        instrument = Instrument.VIOLIN,
        locale = "es",
        role = Role.INDEPENDENT,
        isMinor = isMinor,
        consentStatus = status,
        policyVersion = policyVersion,
        guardian = guardian,
        deletionInProgress = deletion
    )

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
        assertEquals(
            SessionState.ConsentPending(config, true),
            resolve(profile = profile(ConsentStatus.REVOKED, 2, isMinor = true))
        )
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
    fun `granted con la version vigente es Ready y una version superior no (como consentOk del servidor)`() {
        val current = profile()
        assertEquals(SessionState.Ready(current), resolve(profile = current))
        assertEquals(SessionState.ConsentPending(config, false), resolve(profile = profile(policyVersion = 3)))
    }

    @Test
    fun `granted con version anterior o sin version vuelve a ConsentPending`() {
        assertEquals(SessionState.ConsentPending(config, false), resolve(profile = profile(policyVersion = 1)))
        assertEquals(
            SessionState.ConsentPending(config, true),
            resolve(profile = profile(policyVersion = 1, isMinor = true))
        )
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

    @Test
    fun `sin proveedores o con uno desconocido y sin verificar es EmailUnverified (fail closed)`() {
        val empty = AuthUser("u1", "a@b.com", emailVerified = false, providers = emptySet())
        assertEquals(SessionState.EmailUnverified("a@b.com"), resolve(user = empty, profile = null))
        val unknown = empty.copy(providers = setOf(AuthProvider.PASSWORD, AuthProvider.PASSWORD))
        assertEquals(SessionState.EmailUnverified("a@b.com"), resolve(user = unknown, profile = null))
    }

    @Test
    fun `Google con otro proveedor sin verificar no es EmailUnverified`() {
        val both = AuthUser("u1", "a@b.com", false, setOf(AuthProvider.PASSWORD, AuthProvider.GOOGLE))
        assertEquals(SessionState.NeedsProfile, resolve(user = both, profile = null))
    }

    @Test
    fun `granted sin version sola es ConsentPending`() {
        assertEquals(SessionState.ConsentPending(config, false), resolve(profile = profile(policyVersion = null)))
    }

    @Test
    fun `parental_pending con borrado en curso es Loading`() {
        val guardian = GuardianSummary("p***@g***.com", 1)
        val deleting =
            profile(ConsentStatus.PARENTAL_PENDING, null, isMinor = true, guardian = guardian, deletion = true)
        assertEquals(SessionState.Loading, resolve(profile = deleting))
    }

    @Test
    fun `toString de los estados no filtra email ni nombre`() {
        assertFalse(SessionState.EmailUnverified("ana@secreto.com").toString().contains("ana@secreto.com"))
        assertFalse(SessionState.ParentalPending("p***@g***.com", 1).toString().contains("p***"))
    }
}
