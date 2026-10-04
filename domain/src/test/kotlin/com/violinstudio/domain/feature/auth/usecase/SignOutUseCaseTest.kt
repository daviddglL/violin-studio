package com.violinstudio.domain.feature.auth.usecase

import com.violinstudio.domain.feature.FakeAuthRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SignOutUseCaseTest {
    @Test
    fun `cierra la sesion en el repositorio`() = runTest {
        val auth = FakeAuthRepository()
        SignOutUseCase(auth)()
        assertEquals(listOf("signOut"), auth.calls)
    }
}
