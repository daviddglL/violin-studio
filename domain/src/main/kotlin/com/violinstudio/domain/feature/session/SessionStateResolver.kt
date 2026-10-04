package com.violinstudio.domain.feature.session

import com.violinstudio.domain.feature.auth.model.AuthProvider
import com.violinstudio.domain.feature.auth.model.AuthUser
import com.violinstudio.domain.feature.consent.model.IdentityConfig
import com.violinstudio.domain.feature.profile.model.ConsentStatus
import com.violinstudio.domain.feature.profile.model.UserProfile
import javax.inject.Inject

/** Tabla de verdad pura: sin E/S ni edad calculada en cliente (el `isMinor` del servidor prevalece). */
class SessionStateResolver @Inject constructor() {
    operator fun invoke(user: AuthUser?, profile: UserProfile?, config: IdentityConfig): SessionState = when {
        user == null -> SessionState.LoggedOut
        needsEmailVerification(user) -> SessionState.EmailUnverified(user.email)
        profile == null -> SessionState.NeedsProfile
        profile.deletionInProgress -> SessionState.Loading
        profile.consentStatus == ConsentStatus.PARENTAL_PENDING ->
            SessionState.ParentalPending(profile.guardian?.emailMasked, profile.guardian?.sends ?: 0)
        profile.isConsentCurrent(config.policyVersion) -> SessionState.Ready(profile)
        else -> SessionState.ConsentPending(config, profile.isMinor, profile.consentReason())
    }

    // Google verifica el email por su cuenta: nunca se le pide verificar. Fail-closed: sin proveedores o con uno desconocido y sin verificar, sí se pide.
    fun needsEmailVerification(user: AuthUser): Boolean = !user.emailVerified && AuthProvider.GOOGLE !in user.providers
}

private fun UserProfile.consentReason(): ConsentReason = when (consentStatus) {
    ConsentStatus.REVOKED -> ConsentReason.REVOKED
    ConsentStatus.GRANTED -> ConsentReason.POLICY_UPDATED
    else -> ConsentReason.FIRST
}
