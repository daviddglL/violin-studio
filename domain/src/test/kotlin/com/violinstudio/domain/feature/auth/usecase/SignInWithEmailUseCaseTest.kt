package com.violinstudio.domain.feature.auth.usecase

import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.auth.failure.AuthFailure
import com.violinstudio.domain.feature.verifiedUser
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SignInWithEmailUseCaseTest {
    private val auth = FakeAuthRepository()

    @Test
    fun `devuelve el usuario y recorta el email`() = runTest {
        val result = SignInWithEmailUseCase(auth)("  a@b.com ", "secret")
        assertEquals(Result.success(verifiedUser), result)
        assertEquals(listOf("signInWithEmail:a@b.com"), auth.calls)
    }

    @Test
    fun `propaga el fallo del repositorio sin reintentar ni cerrar sesion`() = runTest {
        auth.signInResult = Result.failure(AuthFailure.InvalidCredentials)
        val result = SignInWithEmailUseCase(auth)("a@b.com", "bad")
        assertEquals(AuthFailure.InvalidCredentials, result.exceptionOrNull())
        assertEquals(listOf("signInWithEmail:a@b.com"), auth.calls)
    }
}
