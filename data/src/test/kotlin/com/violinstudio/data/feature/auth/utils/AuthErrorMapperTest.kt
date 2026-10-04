package com.violinstudio.data.feature.auth.utils

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthException
import com.violinstudio.domain.feature.auth.failure.AuthFailure
import java.io.IOException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AuthErrorMapperTest {
    private fun auth(code: String) = FirebaseAuthException(code, "detalle con datos")

    private fun map(code: String, op: AuthOperation = AuthOperation.SIGN_IN) = AuthErrorMapper.map(auth(code), op)

    @Test
    fun `en sign-in contrasena erronea, usuario inexistente y credencial invalida dan el mismo fallo`() {
        val codes = listOf(
            "ERROR_WRONG_PASSWORD",
            "ERROR_USER_NOT_FOUND",
            "ERROR_INVALID_CREDENTIAL",
            "ERROR_USER_DISABLED"
        )
        codes.forEach { assertSame(AuthFailure.InvalidCredentials, map(it), it) }
    }

    @Test
    fun `en sign-in un email mal formado tampoco distingue`() {
        assertSame(AuthFailure.InvalidCredentials, map("ERROR_INVALID_EMAIL"))
    }

    @Test
    fun `en reset el usuario inexistente es UserNotFound para que el caso de uso lo oculte`() {
        assertSame(AuthFailure.UserNotFound, map("ERROR_USER_NOT_FOUND", AuthOperation.PASSWORD_RESET))
        assertSame(AuthFailure.InvalidEmail, map("ERROR_INVALID_EMAIL", AuthOperation.PASSWORD_RESET))
    }

    @Test
    fun `en reautenticacion el usuario inexistente sigue siendo InvalidCredentials`() {
        assertSame(AuthFailure.InvalidCredentials, map("ERROR_USER_NOT_FOUND", AuthOperation.OTHER))
    }

    @Test
    fun `registro mapea email en uso, contrasena debil y email invalido`() {
        assertSame(AuthFailure.EmailAlreadyInUse, map("ERROR_EMAIL_ALREADY_IN_USE", AuthOperation.SIGN_UP))
        assertSame(AuthFailure.WeakPassword, map("ERROR_WEAK_PASSWORD", AuthOperation.SIGN_UP))
        assertSame(AuthFailure.InvalidEmail, map("ERROR_INVALID_EMAIL", AuthOperation.SIGN_UP))
    }

    @Test
    fun `cuenta existente con otro proveedor`() {
        assertSame(AuthFailure.AccountExistsWithOtherProvider, map("ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL"))
        assertSame(AuthFailure.AccountExistsWithOtherProvider, map("ERROR_CREDENTIAL_ALREADY_IN_USE"))
    }

    @Test
    fun `google con credencial rechazada es proveedor no disponible`() {
        assertSame(AuthFailure.ProviderUnavailable, map("ERROR_INVALID_CREDENTIAL", AuthOperation.GOOGLE_SIGN_IN))
    }

    @Test
    fun `requiere login reciente, demasiados intentos y red`() {
        assertSame(AuthFailure.RequiresRecentLogin, map("ERROR_REQUIRES_RECENT_LOGIN"))
        assertSame(AuthFailure.TooManyRequests, map("ERROR_TOO_MANY_REQUESTS"))
        assertSame(AuthFailure.TooManyRequests, AuthErrorMapper.map(FirebaseTooManyRequestsException("x")))
        assertSame(AuthFailure.Network, map("ERROR_NETWORK_REQUEST_FAILED"))
        assertSame(AuthFailure.Network, AuthErrorMapper.map(FirebaseNetworkException("x")))
        assertSame(AuthFailure.Network, AuthErrorMapper.map(IOException("x")))
    }

    @Test
    fun `desconocido conserva la causa y no filtra el mensaje`() {
        val cause = IllegalStateException("alice@example.com")
        val failure = AuthErrorMapper.map(cause)
        assertTrue(failure is AuthFailure.Unknown)
        assertSame(cause, failure.cause)
        assertEquals("Error de autenticación desconocido", failure.message)
        assertTrue(map("ERROR_ALGO_NUEVO") is AuthFailure.Unknown)
    }

    @Test
    fun `un codigo de Firebase sin traduccion se conserva en el fallo desconocido sin el mensaje`() {
        val failure = map("ERROR_INTERNAL_ERROR") as AuthFailure.Unknown
        assertTrue(failure.message!!.contains("ERROR_INTERNAL_ERROR"))
        assertTrue(!failure.message!!.contains("detalle con datos"))
    }
}
