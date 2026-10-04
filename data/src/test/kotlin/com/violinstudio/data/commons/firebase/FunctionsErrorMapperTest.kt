package com.violinstudio.data.commons.firebase

import com.violinstudio.domain.feature.account.failure.AccountFailure
import com.violinstudio.domain.feature.consent.failure.ConsentFailure
import com.violinstudio.domain.feature.profile.failure.ProfileFailure
import com.violinstudio.domain.feature.profile.failure.ProfileField
import java.io.IOException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FunctionsErrorMapperTest {
    private fun error(code: String, reason: String? = null, vararg extra: Pair<String, Any?>) =
        FunctionsCallException(code, reason?.let { mapOf("reason" to it) + extra } ?: extra.toMap())

    private fun profile(e: Throwable) = FunctionsErrorMapper.toProfileFailure(e)

    private fun consent(e: Throwable) = FunctionsErrorMapper.toConsentFailure(e)

    private fun account(e: Throwable) = FunctionsErrorMapper.toAccountFailure(e)

    @Test
    fun `registerProfile mapea cada reason a su fallo de perfil`() {
        assertSame(ProfileFailure.EmailNotVerified, profile(error("PERMISSION_DENIED", "EMAIL_NOT_VERIFIED")))
        assertSame(ProfileFailure.InvalidBirthDate, profile(error("INVALID_ARGUMENT", "INVALID_BIRTH_DATE")))
        assertSame(ProfileFailure.UnderageNotAllowed, profile(error("FAILED_PRECONDITION", "UNDERAGE_NOT_ALLOWED")))
        assertSame(ProfileFailure.NoProfile, profile(error("FAILED_PRECONDITION", "NO_PROFILE")))
    }

    @Test
    fun `INVALID_ARGUMENT conserva el campo conocido y tolera el desconocido`() {
        val known = profile(error("INVALID_ARGUMENT", "INVALID_ARGUMENT", "field" to "displayName"))
        assertEquals(ProfileField.DISPLAY_NAME, (known as ProfileFailure.InvalidInput).field)
        val unknown = profile(error("INVALID_ARGUMENT", "INVALID_ARGUMENT", "field" to "otro"))
        assertNull((unknown as ProfileFailure.InvalidInput).field)
        val none = profile(error("INVALID_ARGUMENT", "INVALID_ARGUMENT"))
        assertNull((none as ProfileFailure.InvalidInput).field)
    }

    @Test
    fun `consentimiento mapea los reasons simples`() {
        val cases = mapOf(
            "EMAIL_NOT_VERIFIED" to ConsentFailure.EmailNotVerified,
            "NO_PROFILE" to ConsentFailure.NoProfile,
            "GUARDIAN_REQUIRED" to ConsentFailure.GuardianRequired,
            "NO_ACTIVE_CONSENT" to ConsentFailure.NoActiveConsent,
            "CONSENT_ALREADY_GRANTED" to ConsentFailure.AlreadyGranted,
            "NOT_MINOR" to ConsentFailure.NotMinor,
            "GUARDIAN_EMAIL_INVALID" to ConsentFailure.GuardianEmailInvalid,
            "UNDERAGE_NOT_ALLOWED" to ConsentFailure.UnderageNotAllowed
        )
        cases.forEach { (reason, expected) ->
            assertSame(expected, consent(error("FAILED_PRECONDITION", reason)), reason)
        }
    }

    @Test
    fun `POLICY_OUTDATED lleva la version vigente, venga como Int o Double`() {
        val int = consent(error("FAILED_PRECONDITION", "POLICY_OUTDATED", "currentVersion" to 3))
        assertEquals(3, (int as ConsentFailure.PolicyOutdated).currentVersion)
        val double = consent(error("FAILED_PRECONDITION", "POLICY_OUTDATED", "currentVersion" to 4.0))
        assertEquals(4, (double as ConsentFailure.PolicyOutdated).currentVersion)
        val missing = consent(error("FAILED_PRECONDITION", "POLICY_OUTDATED"))
        assertNull((missing as ConsentFailure.PolicyOutdated).currentVersion)
    }

    @Test
    fun `RATE_LIMITED lleva retryAfter`() {
        val failure = consent(error("RESOURCE_EXHAUSTED", "RATE_LIMITED", "retryAfterSeconds" to 120))
        assertEquals(120L, (failure as ConsentFailure.RateLimited).retryAfterSeconds)
        val missing = consent(error("RESOURCE_EXHAUSTED", "RATE_LIMITED"))
        assertNull((missing as ConsentFailure.RateLimited).retryAfterSeconds)
    }

    @Test
    fun `consentimiento INVALID_ARGUMENT lleva el campo`() {
        val failure = consent(error("INVALID_ARGUMENT", "INVALID_ARGUMENT", "field" to "policyVersion"))
        assertEquals("policyVersion", (failure as ConsentFailure.InvalidArgument).field)
    }

    @Test
    fun `borrado mapea REAUTH_REQUIRED y ERASURE_FAILED`() {
        assertSame(AccountFailure.RequiresRecentLogin, account(error("FAILED_PRECONDITION", "REAUTH_REQUIRED")))
        assertSame(AccountFailure.ErasureFailed, account(error("INTERNAL", "ERASURE_FAILED")))
    }

    @Test
    fun `sin red o timeout es Network en los tres dominios`() {
        listOf(error("UNAVAILABLE"), error("DEADLINE_EXCEEDED"), IOException("x")).forEach {
            assertSame(ProfileFailure.Network, profile(it))
            assertSame(ConsentFailure.Network, consent(it))
            assertSame(AccountFailure.Network, account(it))
        }
    }

    @Test
    fun `unauthenticated, reasons nuevos y errores raros son Unknown con la causa`() {
        val unauth = error("UNAUTHENTICATED")
        assertTrue(profile(unauth) is ProfileFailure.Unknown)
        assertSame(unauth, consent(unauth).cause)
        assertTrue(account(error("INTERNAL", "FUTURO")) is AccountFailure.Unknown)
        assertTrue(consent(IllegalStateException("x")) is ConsentFailure.Unknown)
    }

    @Test
    fun `details que no es un mapa no revienta`() {
        assertTrue(profile(FunctionsCallException("INTERNAL", "texto")) is ProfileFailure.Unknown)
    }
}
