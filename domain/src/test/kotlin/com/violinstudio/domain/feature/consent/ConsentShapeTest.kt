package com.violinstudio.domain.feature.consent

import com.violinstudio.domain.feature.account.failure.AccountFailure
import com.violinstudio.domain.feature.consent.failure.ConsentFailure
import com.violinstudio.domain.feature.consent.model.IdentityConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConsentShapeTest {
    private fun label(failure: ConsentFailure): String = when (failure) {
        is ConsentFailure.PolicyOutdated -> "outdated"
        ConsentFailure.GuardianRequired -> "guardian"
        is ConsentFailure.RateLimited -> "rate"
        ConsentFailure.GuardianEmailInvalid -> "email"
        ConsentFailure.NotMinor -> "not-minor"
        ConsentFailure.UnderageNotAllowed -> "underage"
        ConsentFailure.NoProfile -> "no-profile"
        ConsentFailure.NoActiveConsent -> "no-consent"
        ConsentFailure.AlreadyGranted -> "granted"
        ConsentFailure.EmailNotVerified -> "unverified"
        ConsentFailure.Network -> "network"
        is ConsentFailure.Unknown -> "unknown"
    }

    private fun label(failure: AccountFailure): String = when (failure) {
        AccountFailure.RequiresRecentLogin -> "recent"
        AccountFailure.ErasureFailed -> "erasure"
        AccountFailure.Network -> "network"
        is AccountFailure.Unknown -> "unknown"
    }

    @Test
    fun `IdentityConfig refleja la respuesta del servidor`() {
        val config = IdentityConfig(1, "https://x/privacy", 14, true)
        assertEquals(14, config.digitalConsentAge)
        assertEquals(1, config.policyVersion)
    }

    @Test
    fun `ConsentFailure lleva la version vigente y la espera`() {
        assertEquals(2, ConsentFailure.PolicyOutdated(currentVersion = 2).currentVersion)
        assertEquals(90L, ConsentFailure.RateLimited(retryAfterSeconds = 90).retryAfterSeconds)
        assertEquals("rate", label(ConsentFailure.RateLimited(null)))
        assertEquals("guardian", label(ConsentFailure.GuardianRequired))
    }

    @Test
    fun `AccountFailure cubre reautenticacion y fallo de borrado`() {
        assertEquals("recent", label(AccountFailure.RequiresRecentLogin))
        assertEquals("erasure", label(AccountFailure.ErasureFailed))
    }
}
