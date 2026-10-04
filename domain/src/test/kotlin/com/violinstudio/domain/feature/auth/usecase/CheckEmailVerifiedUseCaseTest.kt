package com.violinstudio.domain.feature.auth.usecase

import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.auth.failure.AuthFailure
import com.violinstudio.domain.feature.passwordUser
import com.violinstudio.domain.feature.verifiedUser
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CheckEmailVerifiedUseCaseTest {
    private val auth = FakeAuthRepository()

    @Test
    fun `recarga con refresco de token y devuelve true si ya esta verificado`() = runTest {
        auth.reloadResult = Result.success(verifiedUser)
        assertEquals(Result.success(true), CheckEmailVerifiedUseCase(auth)())
        assertEquals(listOf("reloadAndRefreshToken"), auth.calls)
    }

    @Test
    fun `devuelve false si sigue sin verificar`() = runTest {
        auth.reloadResult = Result.success(passwordUser)
        assertEquals(Result.success(false), CheckEmailVerifiedUseCase(auth)())
    }

    @Test
    fun `propaga el fallo de red`() = runTest {
        auth.reloadResult = Result.failure(AuthFailure.Network)
        assertEquals(AuthFailure.Network, CheckEmailVerifiedUseCase(auth)().exceptionOrNull())
    }
}
