package com.violinstudio.domain.feature.auth.usecase

import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.auth.failure.AuthFailure
import com.violinstudio.domain.feature.passwordUser
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SignUpWithEmailUseCaseTest {
    private val auth = FakeAuthRepository()

    @Test
    fun `registra y envia la verificacion`() = runTest {
        val result = SignUpWithEmailUseCase(auth)("a@b.com", "secret123")
        assertEquals(Result.success(passwordUser), result)
        assertEquals(listOf("signUpWithEmail:a@b.com", "sendEmailVerification"), auth.calls)
    }

    @Test
    fun `si falla el envio de verificacion el registro sigue siendo correcto`() = runTest {
        auth.sendVerificationResult = Result.failure(AuthFailure.Network)
        val result = SignUpWithEmailUseCase(auth)("a@b.com", "secret123")
        assertEquals(Result.success(passwordUser), result)
    }

    @Test
    fun `EmailAlreadyInUse no envia verificacion ni toca la sesion`() = runTest {
        auth.signUpResult = Result.failure(AuthFailure.EmailAlreadyInUse)
        val result = SignUpWithEmailUseCase(auth)("a@b.com", "secret123")
        assertEquals(AuthFailure.EmailAlreadyInUse, result.exceptionOrNull())
        assertEquals(listOf("signUpWithEmail:a@b.com"), auth.calls)
        assertEquals(null, auth.user.value)
    }

    @Test
    fun `WeakPassword se propaga sin enviar verificacion`() = runTest {
        auth.signUpResult = Result.failure(AuthFailure.WeakPassword)
        val result = SignUpWithEmailUseCase(auth)("a@b.com", "1")
        assertEquals(AuthFailure.WeakPassword, result.exceptionOrNull())
        assertEquals(listOf("signUpWithEmail:a@b.com"), auth.calls)
    }

    @Test
    fun `un email con formato invalido no llama al repositorio`() = runTest {
        assertEquals(AuthFailure.InvalidEmail, SignUpWithEmailUseCase(auth)("sin-arroba", "secret123").exceptionOrNull())
        assertEquals(emptyList<String>(), auth.calls)
    }
}
