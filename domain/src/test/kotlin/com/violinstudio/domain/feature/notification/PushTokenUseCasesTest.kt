package com.violinstudio.domain.feature.notification

import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.auth.model.AuthProvider
import com.violinstudio.domain.feature.auth.model.AuthUser
import com.violinstudio.domain.feature.notification.usecase.RefreshPushTokenUseCase
import com.violinstudio.domain.feature.notification.usecase.SyncPushTokenUseCase
import com.violinstudio.domain.testing.FakePushTokenRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PushTokenUseCasesTest {
    @Test
    fun `sync registra para el uid dado`() = runTest {
        val push = FakePushTokenRepository()
        assertTrue(SyncPushTokenUseCase(push)("u1").isSuccess)
        assertEquals(listOf("register:u1"), push.calls)
    }

    @Test
    fun `refresh sin sesion no hace nada`() = runTest {
        val push = FakePushTokenRepository()
        assertTrue(RefreshPushTokenUseCase(FakeAuthRepository(), push)("tok").isSuccess)
        assertEquals(emptyList<String>(), push.calls)
    }

    @Test
    fun `refresh con sesion pasa el token nuevo al repositorio`() = runTest {
        val auth = FakeAuthRepository().apply { user = AuthUser("u1", null, true, setOf(AuthProvider.GOOGLE)) }
        val push = FakePushTokenRepository()
        RefreshPushTokenUseCase(auth, push)("tok")
        assertEquals(listOf("onNewToken:u1:tok"), push.calls)
    }
}
