package com.violinstudio.domain.feature.auth.usecase

import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.auth.model.AuthProvider
import com.violinstudio.domain.feature.auth.model.AuthUser
import com.violinstudio.domain.testing.FakePushTokenRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SignOutUseCaseTest {
    private val user = AuthUser("u1", "a@b.c", true, setOf(AuthProvider.PASSWORD))

    @Test
    fun `cierra la sesion en el repositorio`() = runTest {
        val auth = FakeAuthRepository()
        val push = FakePushTokenRepository()
        SignOutUseCase(auth, push)()
        assertEquals(listOf("signOut"), auth.calls)
        assertEquals(emptyList<String>(), push.calls)
    }

    @Test
    fun `con sesion da de baja el token antes de cerrar`() = runTest {
        val auth = FakeAuthRepository().apply { user = this@SignOutUseCaseTest.user }
        val push = FakePushTokenRepository()
        SignOutUseCase(auth, push)()
        assertEquals(listOf("unregister:u1"), push.calls)
        assertEquals(listOf("signOut"), auth.calls)
    }

    @Test
    fun `si la baja falla o tarda demasiado, cierra igual`() = runTest {
        val auth = FakeAuthRepository().apply { user = this@SignOutUseCaseTest.user }
        val push = FakePushTokenRepository().apply {
            result = Result.failure(IllegalStateException("offline"))
            unregisterDelayMs = SignOutUseCase.UNREGISTER_TIMEOUT_MS * 10
        }
        SignOutUseCase(auth, push)()
        assertEquals(listOf("signOut"), auth.calls)
    }
}
