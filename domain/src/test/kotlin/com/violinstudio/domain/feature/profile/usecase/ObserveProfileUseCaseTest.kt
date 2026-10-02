package com.violinstudio.domain.feature.profile.usecase

import app.cash.turbine.test
import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.FakeProfileRepository
import com.violinstudio.domain.feature.userProfile
import com.violinstudio.domain.feature.verifiedUser
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ObserveProfileUseCaseTest {
    private val auth = FakeAuthRepository()
    private val repo = FakeProfileRepository()

    @Test
    fun `sin sesion emite null y no observa`() = runTest {
        ObserveProfileUseCase(auth, repo)().test {
            assertEquals(null, awaitItem())
            expectNoEvents()
        }
        assertEquals(emptyList<String>(), repo.calls)
    }

    @Test
    fun `con sesion observa el perfil del uid y reemite sus cambios`() = runTest {
        auth.user.value = verifiedUser
        ObserveProfileUseCase(auth, repo)().test {
            assertEquals(null, awaitItem())
            repo.profile.value = userProfile()
            assertEquals(userProfile(), awaitItem())
            auth.user.value = null
            assertEquals(null, awaitItem())
        }
        assertEquals(listOf("observe:u1"), repo.calls)
    }
}
