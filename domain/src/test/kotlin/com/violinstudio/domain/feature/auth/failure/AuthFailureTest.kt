package com.violinstudio.domain.feature.auth.failure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AuthFailureTest {
    // `when` sin `else`: si se añade un fallo sin tratar, este test deja de compilar.
    private fun label(failure: AuthFailure): String = when (failure) {
        AuthFailure.InvalidCredentials -> "invalid"
        AuthFailure.EmailAlreadyInUse -> "in-use"
        AuthFailure.WeakPassword -> "weak"
        AuthFailure.AccountExistsWithOtherProvider -> "other-provider"
        AuthFailure.TooManyRequests -> "too-many"
        AuthFailure.Network -> "network"
        AuthFailure.InvalidEmail -> "invalid-email"
        AuthFailure.UserNotFound -> "user-not-found"
        AuthFailure.Cancelled -> "cancelled"
        AuthFailure.ProviderUnavailable -> "provider-unavailable"
        AuthFailure.RequiresRecentLogin -> "recent-login"
        is AuthFailure.Unknown -> "unknown"
    }

    @Test
    fun `la jerarquia sellada cubre los doce fallos de la spec`() {
        val all = listOf(
            AuthFailure.InvalidCredentials,
            AuthFailure.EmailAlreadyInUse,
            AuthFailure.WeakPassword,
            AuthFailure.AccountExistsWithOtherProvider,
            AuthFailure.TooManyRequests,
            AuthFailure.Network,
            AuthFailure.InvalidEmail,
            AuthFailure.UserNotFound,
            AuthFailure.Cancelled,
            AuthFailure.ProviderUnavailable,
            AuthFailure.RequiresRecentLogin,
            AuthFailure.Unknown()
        )
        assertEquals(12, all.map(::label).toSet().size)
    }

    @Test
    fun `es una excepcion para viajar dentro de Result y Unknown conserva la causa`() {
        val cause = IllegalStateException("boom")
        val failure: Throwable = AuthFailure.Unknown(cause)
        assertTrue(failure is Exception)
        assertEquals(cause, failure.cause)
    }
}
