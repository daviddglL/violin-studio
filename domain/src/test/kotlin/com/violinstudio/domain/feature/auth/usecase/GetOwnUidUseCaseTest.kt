package com.violinstudio.domain.feature.auth.usecase

import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.verifiedUser
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GetOwnUidUseCaseTest {
    private val auth = FakeAuthRepository()
    private val useCase = GetOwnUidUseCase(auth)

    @Test
    fun `devuelve el uid del usuario con sesion y null sin ella`() = runTest {
        assertNull(useCase())
        auth.user = verifiedUser
        assertEquals("u1", useCase())
    }
}
