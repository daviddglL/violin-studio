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
        ConsentFailure.GuardianEmailMismatch -> "email-mismatch"
        ConsentFailure.GuardianLegacyConsent -> "legacy-consent"
        ConsentFailure.NotMinor -> "not-minor"
        ConsentFailure.UnderageNotAllowed -> "underage"
        ConsentFailure.NoProfile -> "no-profile"
        ConsentFailure.NoActiveConsent -> "no-consent"
        ConsentFailure.AlreadyGranted -> "granted"
        ConsentFailure.EmailNotVerified -> "unverified"
        is ConsentFailure.InvalidArgument -> "invalid-argument"
        ConsentFailure.RecordingDisabled -> "recording-disabled"
        is ConsentFailure.RecordingConsentOutdated -> "recording-outdated"
        ConsentFailure.TemporarilyUnavailable -> "unavailable"
        ConsentFailure.Network -> "network"
        is ConsentFailure.Unknown -> "unknown"
    }

    private fun label(failure: AccountFailure): String = when (failure) {
        AccountFailure.RequiresRecentLogin -> "recent"
        AccountFailure.ErasureFailed -> "erasure"
        AccountFailure.Network -> "network"
        AccountFailure.Unauthenticated -> "unauthenticated"
        is AccountFailure.Unknown -> "unknown"
    }

    @Test
    fun `IdentityConfig refleja la respuesta del servidor`() {
        val config = IdentityConfig(1, "https://x/privacy", 14, true)
        assertEquals(14, config.digitalConsentAge)
        assertEquals(1, config.policyVersion)
    }

    @Test
    fun `IdentityConfig lleva los interruptores de grabacion apagados por defecto y la version del consentimiento`() {
        val off = IdentityConfig(1, "https://x/privacy", 14, true)
        assertEquals(false, off.recordingsEnabled)
        assertEquals(false, off.videoEnabled)
        assertEquals(false, off.minorRecordingEnabled)
        assertEquals(1, off.recordingConsentVersion)
        val on = off.copy(
            recordingsEnabled = true,
            videoEnabled = true,
            minorRecordingEnabled = true,
            recordingConsentVersion = 3
        )
        assertEquals(true, on.recordingsEnabled)
        assertEquals(3, on.recordingConsentVersion)
    }

    @Test
    fun `ConsentFailure lleva la version vigente y la espera`() {
        assertEquals(2, ConsentFailure.PolicyOutdated(currentVersion = 2).currentVersion)
        assertEquals(90L, ConsentFailure.RateLimited(retryAfterSeconds = 90).retryAfterSeconds)
        assertEquals("rate", label(ConsentFailure.RateLimited(null)))
        assertEquals("guardian", label(ConsentFailure.GuardianRequired))
        assertEquals("policyVersion", ConsentFailure.InvalidArgument(field = "policyVersion").field)
        assertEquals("invalid-argument", label(ConsentFailure.InvalidArgument(null)))
        assertEquals(ConsentFailure.PolicyOutdated(2), ConsentFailure.PolicyOutdated(2))
        assertEquals(ConsentFailure.RateLimited(90), ConsentFailure.RateLimited(90))
        assertEquals(ConsentFailure.InvalidArgument("x"), ConsentFailure.InvalidArgument("x"))
    }

    @Test
    fun `AccountFailure cubre reautenticacion y fallo de borrado`() {
        assertEquals("recent", label(AccountFailure.RequiresRecentLogin))
        assertEquals("erasure", label(AccountFailure.ErasureFailed))
        assertEquals("unauthenticated", label(AccountFailure.Unauthenticated))
    }
}
