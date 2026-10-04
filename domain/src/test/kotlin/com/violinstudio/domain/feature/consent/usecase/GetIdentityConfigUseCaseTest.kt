package com.violinstudio.domain.feature.consent.usecase

import com.violinstudio.domain.feature.FakeConsentRepository
import com.violinstudio.domain.feature.config
import com.violinstudio.domain.feature.consent.failure.ConsentFailure
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GetIdentityConfigUseCaseTest {
    private val consent = FakeConsentRepository()

    @Test
    fun `devuelve la configuracion del servidor`() = runTest {
        assertEquals(Result.success(config), GetIdentityConfigUseCase(consent)())
        assertEquals(listOf("identityConfig"), consent.calls)
    }

    @Test
    fun `propaga el fallo`() = runTest {
        consent.configResult = Result.failure(ConsentFailure.Network)
        assertEquals(ConsentFailure.Network, GetIdentityConfigUseCase(consent)().exceptionOrNull())
    }
}
