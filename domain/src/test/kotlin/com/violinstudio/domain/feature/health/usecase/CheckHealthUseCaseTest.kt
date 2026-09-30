package com.violinstudio.domain.feature.health.usecase

import com.violinstudio.domain.feature.health.model.HealthInfo
import com.violinstudio.domain.feature.health.repository.HealthRepository
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CheckHealthUseCaseTest {
    @Test
    fun `devuelve lo que devuelve el repositorio`() = runTest {
        val ok = Result.success(HealthInfo("ok", "0.1.0"))
        assertEquals(ok, CheckHealthUseCase(HealthRepository { ok })())

        val error = IOException("sin red")
        assertEquals(error, CheckHealthUseCase(HealthRepository { Result.failure(error) })().exceptionOrNull())
    }
}
