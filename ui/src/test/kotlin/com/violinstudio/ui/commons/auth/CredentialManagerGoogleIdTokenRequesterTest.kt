package com.violinstudio.ui.commons.auth

import android.content.Context
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.NoCredentialException
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CredentialManagerGoogleIdTokenRequesterTest {
    private val context = mockk<Context>()

    private class RecordingSource(private val answer: () -> String) : GoogleCredentialSource {
        val options = mutableListOf<GoogleIdRequestSpec>()

        override suspend fun fetch(context: Context, spec: GoogleIdRequestSpec): String {
            options += spec
            return answer()
        }
    }

    private fun requester(source: GoogleCredentialSource, clientId: String? = "web-client.apps.test") =
        CredentialManagerGoogleIdTokenRequester(GoogleSignInConfig(clientId), source)

    @Test
    fun `the options carry the injected server client id and an explicit sign-in policy`() {
        val option = GoogleIdOptionFactory.create("web-client.apps.test", "nonce-1")
        assertEquals("web-client.apps.test", option.serverClientId)
        assertEquals(GoogleIdOptionFactory.sha256Hex("nonce-1"), option.nonce)
        assertNotEquals("nonce-1", option.nonce)
        assertFalse(option.filterByAuthorizedAccounts)
        assertFalse(option.autoSelectEnabled)
    }

    @Test
    fun `the nonce is sent as the lowercase hex SHA-256 of the raw nonce`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            GoogleIdOptionFactory.sha256Hex("abc")
        )
    }

    @Test
    fun `every request uses a fresh unpredictable nonce`() {
        val nonces = List(50) { GoogleIdOptionFactory.newNonce() }
        assertEquals(50, nonces.toSet().size)
        assertTrue(nonces.all { it.length >= 32 })
    }

    @Test
    fun `two requests send different nonces and the token is delivered`() = runTest {
        val source = RecordingSource { "jwt" }
        val requester = requester(source)
        val first = requester.request(context)
        requester.request(context)
        assertEquals("jwt", (first as GoogleIdTokenResult.Token).token.value)
        val firstToken = (first as GoogleIdTokenResult.Token).token
        val rawNonce = checkNotNull(firstToken.rawNonce)
        assertEquals(source.options[0].nonce, GoogleIdOptionFactory.sha256Hex(rawNonce))
        assertNotEquals(rawNonce, source.options[0].nonce)
        assertEquals(2, source.options.size)
        assertNotEquals(source.options[0].nonce, source.options[1].nonce)
        assertEquals("web-client.apps.test", source.options[0].serverClientId)
    }

    @Test
    fun `the user dismissing the sheet is a cancellation`() = runTest {
        val source = RecordingSource { throw GetCredentialCancellationException() }
        assertEquals(GoogleIdTokenResult.Cancelled, requester(source).request(context))
    }

    @Test
    fun `no credential or any other credential failure means the provider is unavailable`() = runTest {
        listOf<Throwable>(NoCredentialException(), GetCredentialUnknownException(), IllegalStateException("boom"))
            .forEach { failure ->
                val source = RecordingSource { throw failure }
                assertEquals(GoogleIdTokenResult.ProviderUnavailable, requester(source).request(context), "$failure")
            }
    }

    @Test
    fun `a blank id from the source is unavailable and a missing client id never reaches Credential Manager`() =
        runTest {
            assertEquals(GoogleIdTokenResult.ProviderUnavailable, requester(RecordingSource { " " }).request(context))
            for (clientId in listOf(null, "", "  ")) {
                val source = RecordingSource { "jwt" }
                assertEquals(GoogleIdTokenResult.ProviderUnavailable, requester(source, clientId).request(context))
                assertTrue(source.options.isEmpty())
            }
        }

    @Test
    fun `coroutine cancellation is never swallowed`() = runTest {
        val source = RecordingSource { throw CancellationException("scope cancelled") }
        val outcome = runCatching { requester(source).request(context) }
        assertTrue(outcome.exceptionOrNull() is CancellationException)
    }
}
