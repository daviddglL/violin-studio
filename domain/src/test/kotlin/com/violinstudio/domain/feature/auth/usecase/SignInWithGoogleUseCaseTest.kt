package com.violinstudio.domain.feature.auth.usecase

import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.auth.failure.AuthFailure
import com.violinstudio.domain.feature.auth.model.GoogleIdToken
import com.violinstudio.domain.feature.verifiedUser
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SignInWithGoogleUseCaseTest {
    private val auth = FakeAuthRepository()
    private val token = GoogleIdToken("tok")

    @Test
    fun `devuelve el usuario`() = runTest {
        assertEquals(Result.success(verifiedUser), SignInWithGoogleUseCase(auth)(token))
    }

    @Test
    fun `Cancelled no es un error`() = runTest {
        auth.googleResult = Result.failure(AuthFailure.Cancelled)
        val result = SignInWithGoogleUseCase(auth)(token)
        assertTrue(result.isSuccess)
        assertEquals(null, result.getOrNull())
    }

    @Test
    fun `los demas fallos se propagan`() = runTest {
        auth.googleResult = Result.failure(AuthFailure.AccountExistsWithOtherProvider)
        val result = SignInWithGoogleUseCase(auth)(token)
        assertEquals(AuthFailure.AccountExistsWithOtherProvider, result.exceptionOrNull())
    }
}
