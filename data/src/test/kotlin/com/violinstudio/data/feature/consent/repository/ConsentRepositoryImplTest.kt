package com.violinstudio.data.feature.consent.repository

import com.violinstudio.data.commons.firebase.FunctionsCallException
import com.violinstudio.data.feature.profile.datasource.FakeIdentityFunctionsDataSource
import com.violinstudio.domain.feature.consent.failure.ConsentFailure
import com.violinstudio.domain.feature.consent.model.GuardianRequestReceipt
import com.violinstudio.domain.feature.consent.model.IdentityConfig
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ConsentRepositoryImplTest {
    private val functions = FakeIdentityFunctionsDataSource()
    private val repo = ConsentRepositoryImpl(functions)

    private fun failure(reason: String, vararg extra: Pair<String, Any?>) =
        FunctionsCallException("FAILED_PRECONDITION", mapOf("reason" to reason) + extra)

    private fun config(
        version: Any? = 1,
        url: Any? = "u",
        age: Any? = 14,
        flow: Any? = true
    ) = mapOf(
        "policyVersion" to version,
        "policyUrl" to url,
        "digitalConsentAge" to age,
        "guardianFlowEnabled" to flow
    )

    @Test
    fun `identityConfig parsea la politica y tolera numeros como Double`() = runTest {
        functions.response = config(3.0, "https://x.test/p", 14L, true) + ("extra" to "ignorado")
        assertEquals(IdentityConfig(3, "https://x.test/p", 14, true), repo.identityConfig().getOrThrow())
        assertEquals(listOf("identityConfig"), functions.calls.map { it.first })
    }

    @Test
    fun `identityConfig con respuesta malformada es Unknown`() = runTest {
        val bad = listOf(
            null,
            "texto",
            mapOf("policyVersion" to 1),
            config(version = "1"),
            config(version = 1.5),
            config(url = null),
            config(age = "14"),
            config(flow = "si")
        )
        bad.forEach {
            functions.response = it
            val e = repo.identityConfig().exceptionOrNull()
            assertTrue(e is ConsentFailure.Unknown, "esperaba Unknown para $it y fue $e")
        }
    }

    @Test
    fun `identityConfig sin red es Network`() = runTest {
        functions.failure = IOException("sin red")
        assertEquals(ConsentFailure.Network, repo.identityConfig().exceptionOrNull())
    }

    @Test
    fun `recordConsent envia la version y acepta cualquier respuesta correcta`() = runTest {
        functions.response = mapOf("consentStatus" to "granted")
        assertTrue(repo.recordConsent(3).isSuccess)
        functions.response = null
        assertTrue(repo.recordConsent(3).isSuccess)
        assertEquals(listOf("recordConsent" to 3, "recordConsent" to 3), functions.calls)
    }

    @Test
    fun `recordConsent mapea POLICY_OUTDATED con la version vigente`() = runTest {
        functions.failure = failure("POLICY_OUTDATED", "currentVersion" to 4)
        assertEquals(ConsentFailure.PolicyOutdated(4), repo.recordConsent(3).exceptionOrNull())
    }

    @Test
    fun `recordConsent mapea GUARDIAN_REQUIRED`() = runTest {
        functions.failure = failure("GUARDIAN_REQUIRED")
        assertEquals(ConsentFailure.GuardianRequired, repo.recordConsent(1).exceptionOrNull())
    }

    @Test
    fun `revokeConsent llama al callable y mapea NO_ACTIVE_CONSENT`() = runTest {
        assertTrue(repo.revokeConsent().isSuccess)
        functions.failure = failure("NO_ACTIVE_CONSENT")
        assertEquals(ConsentFailure.NoActiveConsent, repo.revokeConsent().exceptionOrNull())
        assertEquals(listOf("revokeConsent", "revokeConsent"), functions.calls.map { it.first })
    }

    @Test
    fun `requestGuardianConsent devuelve el email enmascarado`() = runTest {
        functions.response = mapOf("status" to "sent", "emailMasked" to "p***@x.com")
        assertEquals(GuardianRequestReceipt("p***@x.com"), repo.requestGuardianConsent("padre@x.com").getOrThrow())
        assertEquals(listOf("requestGuardianConsent" to "padre@x.com"), functions.calls)
    }

    @Test
    fun `requestGuardianConsent sin emailMasked es Unknown`() = runTest {
        functions.response = mapOf("status" to "sent")
        assertTrue(repo.requestGuardianConsent("a@b.co").exceptionOrNull() is ConsentFailure.Unknown)
    }

    @Test
    fun `requestGuardianConsent mapea los motivos del servidor`() = runTest {
        val cases = listOf(
            failure("RATE_LIMITED", "retryAfterSeconds" to 60) to ConsentFailure.RateLimited(60),
            failure("GUARDIAN_EMAIL_INVALID") to ConsentFailure.GuardianEmailInvalid,
            failure("NOT_MINOR") to ConsentFailure.NotMinor,
            failure("CONSENT_ALREADY_GRANTED") to ConsentFailure.AlreadyGranted
        )
        cases.forEach { (error, expected) ->
            functions.failure = error
            assertEquals(expected, repo.requestGuardianConsent("a@b.co").exceptionOrNull())
        }
    }

    @Test
    fun `la cancelacion no se convierte en fallo`() = runTest {
        functions.failure = CancellationException("cancelada")
        assertThrows<CancellationException> { repo.revokeConsent() }
    }
}
