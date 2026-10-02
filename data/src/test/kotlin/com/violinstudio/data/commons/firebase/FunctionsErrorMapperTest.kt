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

    @Test
    fun `registerProfile mapea cada reason a su fallo de perfil`() {
        assertSame(ProfileFailure.EmailNotVerified, FunctionsErrorMapper.toProfileFailure(error("PERMISSION_DENIED", "EMAIL_NOT_VERIFIED")))
        assertSame(ProfileFailure.InvalidBirthDate, FunctionsErrorMapper.toProfileFailure(error("INVALID_ARGUMENT", "INVALID_BIRTH_DATE")))
        assertSame(ProfileFailure.UnderageNotAllowed, FunctionsErrorMapper.toProfileFailure(error("FAILED_PRECONDITION", "UNDERAGE_NOT_ALLOWED")))
        assertSame(ProfileFailure.NoProfile, FunctionsErrorMapper.toProfileFailure(error("FAILED_PRECONDITION", "NO_PROFILE")))
    }

    @Test
    fun `INVALID_ARGUMENT conserva el campo conocido y tolera el desconocido`() {
        val known = FunctionsErrorMapper.toProfileFailure(error("INVALID_ARGUMENT", "INVALID_ARGUMENT", "field" to "displayName"))
        assertEquals(ProfileField.DISPLAY_NAME, (known as ProfileFailure.InvalidInput).field)
        val unknown = FunctionsErrorMapper.toProfileFailure(error("INVALID_ARGUMENT", "INVALID_ARGUMENT", "field" to "otro"))
        assertNull((unknown as ProfileFailure.InvalidInput).field)
        val none = FunctionsErrorMapper.toProfileFailure(error("INVALID_ARGUMENT", "INVALID_ARGUMENT"))
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
            assertSame(expected, FunctionsErrorMapper.toConsentFailure(error("FAILED_PRECONDITION", reason)), reason)
        }
    }

    @Test
    fun `POLICY_OUTDATED lleva la version vigente, venga como Int o Double`() {
        val int = FunctionsErrorMapper.toConsentFailure(error("FAILED_PRECONDITION", "POLICY_OUTDATED", "currentVersion" to 3))
        assertEquals(3, (int as ConsentFailure.PolicyOutdated).currentVersion)
        val double = FunctionsErrorMapper.toConsentFailure(error("FAILED_PRECONDITION", "POLICY_OUTDATED", "currentVersion" to 4.0))
        assertEquals(4, (double as ConsentFailure.PolicyOutdated).currentVersion)
        val missing = FunctionsErrorMapper.toConsentFailure(error("FAILED_PRECONDITION", "POLICY_OUTDATED"))
        assertNull((missing as ConsentFailure.PolicyOutdated).currentVersion)
    }

    @Test
    fun `RATE_LIMITED lleva retryAfter`() {
        val failure = FunctionsErrorMapper.toConsentFailure(error("RESOURCE_EXHAUSTED", "RATE_LIMITED", "retryAfterSeconds" to 120))
        assertEquals(120L, (failure as ConsentFailure.RateLimited).retryAfterSeconds)
        val missing = FunctionsErrorMapper.toConsentFailure(error("RESOURCE_EXHAUSTED", "RATE_LIMITED"))
        assertNull((missing as ConsentFailure.RateLimited).retryAfterSeconds)
    }

    @Test
    fun `consentimiento INVALID_ARGUMENT lleva el campo`() {
        val failure = FunctionsErrorMapper.toConsentFailure(error("INVALID_ARGUMENT", "INVALID_ARGUMENT", "field" to "policyVersion"))
        assertEquals("policyVersion", (failure as ConsentFailure.InvalidArgument).field)
    }

    @Test
    fun `borrado mapea REAUTH_REQUIRED y ERASURE_FAILED`() {
        assertSame(AccountFailure.RequiresRecentLogin, FunctionsErrorMapper.toAccountFailure(error("FAILED_PRECONDITION", "REAUTH_REQUIRED")))
        assertSame(AccountFailure.ErasureFailed, FunctionsErrorMapper.toAccountFailure(error("INTERNAL", "ERASURE_FAILED")))
    }

    @Test
    fun `sin red o timeout es Network en los tres dominios`() {
        listOf(error("UNAVAILABLE"), error("DEADLINE_EXCEEDED"), IOException("x")).forEach {
            assertSame(ProfileFailure.Network, FunctionsErrorMapper.toProfileFailure(it))
            assertSame(ConsentFailure.Network, FunctionsErrorMapper.toConsentFailure(it))
            assertSame(AccountFailure.Network, FunctionsErrorMapper.toAccountFailure(it))
        }
    }

    @Test
    fun `unauthenticated, reasons nuevos y errores raros son Unknown con la causa`() {
        val unauth = error("UNAUTHENTICATED")
        assertTrue(FunctionsErrorMapper.toProfileFailure(unauth) is ProfileFailure.Unknown)
        assertSame(unauth, FunctionsErrorMapper.toConsentFailure(unauth).cause)
        assertTrue(FunctionsErrorMapper.toAccountFailure(error("INTERNAL", "FUTURO")) is AccountFailure.Unknown)
        assertTrue(FunctionsErrorMapper.toConsentFailure(IllegalStateException("x")) is ConsentFailure.Unknown)
    }

    @Test
    fun `details que no es un mapa no revienta`() {
        val e = FunctionsCallException("INTERNAL", "texto")
        assertTrue(FunctionsErrorMapper.toProfileFailure(e) is ProfileFailure.Unknown)
    }
}
