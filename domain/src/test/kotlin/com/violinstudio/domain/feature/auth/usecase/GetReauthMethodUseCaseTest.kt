package com.violinstudio.domain.feature.auth.usecase

import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.auth.model.AuthProvider
import com.violinstudio.domain.feature.passwordUser
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GetReauthMethodUseCaseTest {
    private val auth = FakeAuthRepository()
    private val useCase = GetReauthMethodUseCase(auth)

    @Test
    fun `password provider reauthenticates with the password`() = runTest {
        auth.user = passwordUser
        assertEquals(ReauthMethod.PASSWORD, useCase())
    }

    @Test
    fun `google only reauthenticates with google`() = runTest {
        auth.user = passwordUser.copy(providers = setOf(AuthProvider.GOOGLE))
        assertEquals(ReauthMethod.GOOGLE, useCase())
    }

    @Test
    fun `both providers prefer the password`() = runTest {
        auth.user = passwordUser.copy(providers = setOf(AuthProvider.GOOGLE, AuthProvider.PASSWORD))
        assertEquals(ReauthMethod.PASSWORD, useCase())
    }

    @Test
    fun `no session or no provider means there is no way to reauthenticate`() = runTest {
        assertEquals(ReauthMethod.NONE, useCase())
        auth.user = passwordUser.copy(providers = emptySet())
        assertEquals(ReauthMethod.NONE, useCase())
    }
}
