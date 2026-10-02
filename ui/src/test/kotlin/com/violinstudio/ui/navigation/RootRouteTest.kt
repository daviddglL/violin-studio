package com.violinstudio.ui.navigation

import com.violinstudio.domain.feature.consent.model.IdentityConfig
import com.violinstudio.domain.feature.profile.model.ConsentStatus
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.profile.model.Role
import com.violinstudio.domain.feature.profile.model.UserProfile
import com.violinstudio.domain.feature.session.SessionState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RootRouteTest {
    private val config = IdentityConfig(1, "https://example.test/policy", 14, true)
    private val profile = UserProfile(
        uid = "u1",
        displayName = "Ana",
        instrument = Instrument.VIOLIN,
        locale = "es",
        role = Role.INDEPENDENT,
        isMinor = false,
        consentStatus = ConsentStatus.GRANTED,
        policyVersion = 1,
        guardian = null,
        deletionInProgress = false
    )

    private val table: List<Pair<SessionState, Any>> = listOf(
        SessionState.Loading to SplashDestination,
        SessionState.Unavailable to OfflineDestination,
        SessionState.LoggedOut to AuthDestination,
        SessionState.EmailUnverified("a@b.c") to VerifyEmailDestination,
        SessionState.NeedsProfile to OnboardingDestination,
        SessionState.ConsentPending(config, isMinor = false) to ConsentDestination,
        SessionState.ConsentPending(config, isMinor = true) to ConsentDestination,
        SessionState.ParentalPending("a***@b.c", 1) to GuardianWaitDestination,
        SessionState.Ready(profile) to HomeDestination
    )

    @Test
    fun `every session state maps to its root route`() {
        table.forEach { (state, route) -> assertEquals(route, state.rootRoute(), "route of $state") }
    }

    @Test
    fun `every non-Ready state maps to an explicit non-business route`() {
        val nonBusiness = setOf(
            SplashDestination, OfflineDestination, AuthDestination, VerifyEmailDestination,
            OnboardingDestination, ConsentDestination, GuardianWaitDestination
        )
        table.filter { it.first !is SessionState.Ready }.forEach { (state, _) ->
            assertTrue(state.rootRoute() in nonBusiness, "route of $state must be a known non-business route")
        }
    }
}
