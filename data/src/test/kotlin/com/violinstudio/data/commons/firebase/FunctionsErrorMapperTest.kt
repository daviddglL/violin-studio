package com.violinstudio.data.commons.firebase

import com.violinstudio.domain.feature.account.failure.AccountFailure
import com.violinstudio.domain.feature.consent.failure.ConsentFailure
import com.violinstudio.domain.feature.link.failure.LinkFailure
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

    private fun link(e: Throwable) = FunctionsErrorMapper.toLinkFailure(e)

    @Test
    fun `vinculo mapea cada reason del servidor segun la tabla de LinkFailure`() {
        val table = mapOf(
            "INVALID_ARGUMENT" to ("INVALID_ARGUMENT" to LinkFailure.InvalidCode),
            "CODE_NOT_FOUND" to ("NOT_FOUND" to LinkFailure.InvalidCode),
            "TEACHER_FULL" to ("RESOURCE_EXHAUSTED" to LinkFailure.TeacherFull),
            "CODE_LIMIT_REACHED" to ("RESOURCE_EXHAUSTED" to LinkFailure.CodeLimitReached),
            "ALREADY_LINKED" to ("FAILED_PRECONDITION" to LinkFailure.AlreadyLinked),
            "NOTICE_OUTDATED" to ("FAILED_PRECONDITION" to LinkFailure.NoticeOutdated),
            "NO_ACTIVE_CONSENT" to ("FAILED_PRECONDITION" to LinkFailure.NoActiveConsent),
            "NO_PROFILE" to ("FAILED_PRECONDITION" to LinkFailure.NoActiveConsent),
            "NOT_ELIGIBLE" to ("PERMISSION_DENIED" to LinkFailure.NotEligible),
            "NOT_TEACHER" to ("PERMISSION_DENIED" to LinkFailure.NotTeacher),
            "NOT_LINK_PARTY" to ("PERMISSION_DENIED" to LinkFailure.PermissionDenied),
            "TEMPORARILY_UNAVAILABLE" to ("UNAVAILABLE" to LinkFailure.TemporarilyUnavailable)
        )
        table.forEach { (reason, expected) -> assertSame(expected.second, link(error(expected.first, reason)), reason) }
    }

    @Test
    fun `vinculo RATE_LIMITED lleva retryAfterSeconds y tolera que falte`() {
        val limited = error("RESOURCE_EXHAUSTED", "RATE_LIMITED", "retryAfterSeconds" to 90.0)
        assertEquals(LinkFailure.RateLimited(90L), link(limited))
        assertEquals(LinkFailure.RateLimited(null), link(error("RESOURCE_EXHAUSTED", "RATE_LIMITED")))
    }

    @Test
    fun `vinculo sin red es Offline`() {
        assertSame(LinkFailure.Offline, link(IOException("sin red")))
        assertSame(LinkFailure.Offline, link(error("UNAVAILABLE")))
        assertSame(LinkFailure.Offline, link(error("DEADLINE_EXCEEDED")))
    }

    @Test
    fun `vinculo con reason nuevo, sin reason o malformado es Unknown`() {
        assertSame(LinkFailure.Unknown, link(error("INTERNAL", "FUTURO")))
        assertSame(LinkFailure.Unknown, link(error("UNAUTHENTICATED")))
        assertSame(LinkFailure.Unknown, link(IllegalStateException("x")))
        assertSame(LinkFailure.Unknown, link(FunctionsCallException("INTERNAL", "texto")))
    }

    @Test
    fun `INTERNAL con IOException en la cadena de causas es sin red, tambien en identidad`() {
        val wrapped = FunctionsCallException("INTERNAL", null, RuntimeException("sdk", IOException("sin red")))
        assertSame(LinkFailure.Offline, link(wrapped))
        assertSame(ConsentFailure.Network, consent(wrapped))
        assertSame(AccountFailure.Network, account(wrapped))
        assertSame(ProfileFailure.Network, profile(wrapped))
        assertSame(LinkFailure.Unknown, link(FunctionsCallException("INTERNAL", null, RuntimeException("x"))))
    }

    @Test
    fun `el reason del servidor gana a una causa IOException`() {
        val limited = FunctionsCallException("UNAVAILABLE", mapOf("reason" to "TEMPORARILY_UNAVAILABLE"), IOException())
        assertSame(LinkFailure.TemporarilyUnavailable, link(limited))
    }
}
