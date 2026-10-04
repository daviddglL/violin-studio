package com.violinstudio.ui.commons.auth

import android.content.Context
import com.violinstudio.domain.feature.auth.model.GoogleIdToken
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Contrato de [GoogleIdTokenRequester]: un fake por cada resultado posible y el comportamiento por defecto. */
class GoogleRequesterContractTest {
    private val context = mockk<Context>()

    @Test
    fun `a requester can deliver a token, a cancellation or an unavailable provider`() = runTest {
        val token = GoogleIdToken("jwt")
        val requesters = listOf(
            FakeGoogleIdTokenRequester(GoogleIdTokenResult.Token(token)),
            FakeGoogleIdTokenRequester(GoogleIdTokenResult.Cancelled),
            FakeGoogleIdTokenRequester(GoogleIdTokenResult.ProviderUnavailable)
        )
        val results = requesters.map { it.request(context) }
        assertSame(token, (results[0] as GoogleIdTokenResult.Token).token)
        assertEquals(GoogleIdTokenResult.Cancelled, results[1])
        assertEquals(GoogleIdTokenResult.ProviderUnavailable, results[2])
    }

    @Test
    fun `without a provided requester Google is unavailable instead of crashing`() = runTest {
        assertEquals(GoogleIdTokenResult.ProviderUnavailable, UnavailableGoogleIdTokenRequester.request(context))
    }

    @Test
    fun `the token result never prints the token`() {
        val text = GoogleIdTokenResult.Token(GoogleIdToken("super-secret-jwt")).toString()
        assertFalse(text.contains("super-secret-jwt"))
        assertTrue(text.contains("Token"))
    }
}
