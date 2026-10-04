package com.violinstudio.domain.feature.auth.usecase

import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.verifiedUser
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GetOwnEmailUseCaseTest {
    private val auth = FakeAuthRepository()
    private val useCase = GetOwnEmailUseCase(auth)

    @Test
    fun `devuelve el email del usuario con sesion`() = runTest {
        auth.user = verifiedUser
        assertEquals(verifiedUser.email, useCase())
    }

    @Test
    fun `sin sesion o sin email devuelve null`() = runTest {
        assertNull(useCase())
        auth.user = verifiedUser.copy(email = null)
        assertNull(useCase())
    }
}
