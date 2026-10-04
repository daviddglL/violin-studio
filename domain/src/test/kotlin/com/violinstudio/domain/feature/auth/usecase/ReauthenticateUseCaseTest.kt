package com.violinstudio.domain.feature.auth.usecase

import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.auth.failure.AuthFailure
import com.violinstudio.domain.feature.auth.model.GoogleIdToken
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReauthenticateUseCaseTest {
    private val auth = FakeAuthRepository()

    @Test
    fun `con contrasena reautentica con password`() = runTest {
        assertEquals(Result.success(Unit), ReauthenticateUseCase(auth)("secret"))
        assertEquals(listOf("reauthPassword"), auth.calls)
    }

    @Test
    fun `con token de Google reautentica con Google`() = runTest {
        assertEquals(Result.success(Unit), ReauthenticateUseCase(auth)(GoogleIdToken("tok")))
        assertEquals(listOf("reauthGoogle"), auth.calls)
    }

    @Test
    fun `propaga credenciales invalidas`() = runTest {
        auth.reauthResult = Result.failure(AuthFailure.InvalidCredentials)
        assertEquals(AuthFailure.InvalidCredentials, ReauthenticateUseCase(auth)("bad").exceptionOrNull())
    }
}
