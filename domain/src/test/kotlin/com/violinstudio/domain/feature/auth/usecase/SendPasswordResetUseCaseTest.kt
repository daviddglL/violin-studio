package com.violinstudio.domain.feature.auth.usecase

import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.auth.failure.AuthFailure
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SendPasswordResetUseCaseTest {
    private val auth = FakeAuthRepository()

    @Test
    fun `formato invalido no llama al repositorio`() = runTest {
        for (bad in listOf("", "   ", "sin-arroba", "a@", "@b.com", "a b@c.com", "a@b")) {
            assertEquals(AuthFailure.InvalidEmail, SendPasswordResetUseCase(auth)(bad).exceptionOrNull(), bad)
        }
        assertTrue(auth.calls.isEmpty())
    }

    @Test
    fun `envia con el email recortado`() = runTest {
        assertEquals(Result.success(Unit), SendPasswordResetUseCase(auth)(" a@b.com "))
        assertEquals(listOf("sendPasswordReset:a@b.com"), auth.calls)
    }

    @Test
    fun `respuesta uniforme, usuario inexistente se trata como enviado`() = runTest {
        auth.resetResult = Result.failure(AuthFailure.UserNotFound)
        assertEquals(Result.success(Unit), SendPasswordResetUseCase(auth)("nadie@b.com"))
    }

    @Test
    fun `el resto de fallos se informan como errores`() = runTest {
        for (failure in listOf(AuthFailure.ProviderUnavailable, AuthFailure.InvalidCredentials, AuthFailure.Unknown())) {
            auth.resetResult = Result.failure(failure)
            assertEquals(failure, SendPasswordResetUseCase(auth)("a@b.com").exceptionOrNull())
        }
        auth.resetResult = Result.failure(AuthFailure.Network)
        assertEquals(AuthFailure.Network, SendPasswordResetUseCase(auth)("a@b.com").exceptionOrNull())
        auth.resetResult = Result.failure(AuthFailure.TooManyRequests)
        assertEquals(AuthFailure.TooManyRequests, SendPasswordResetUseCase(auth)("a@b.com").exceptionOrNull())
    }
}
