package com.violinstudio.data.feature.health.repository

import com.violinstudio.data.feature.health.dto.HealthDto
import com.violinstudio.domain.feature.health.failure.ServerUnavailableException
import com.violinstudio.domain.feature.health.model.HealthInfo
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HealthRepositoryImplTest {
    @Test
    fun `devuelve success cuando el servidor responde ok`() = runTest {
        val repo = HealthRepositoryImpl { HealthDto("ok", "0.1.0") }
        assertEquals(Result.success(HealthInfo("ok", "0.1.0")), repo.check())
    }

    @Test
    fun `devuelve ServerUnavailableException cuando status no es ok`() = runTest {
        val error = HealthRepositoryImpl { HealthDto("degraded", "0.1.0") }.check().exceptionOrNull()
        assertTrue(error is ServerUnavailableException)
        assertEquals("degraded", (error as ServerUnavailableException).status)
    }

    @Test
    fun `convierte los errores de red en failure`() = runTest {
        val error = HealthRepositoryImpl { throw IOException("sin red") }.check().exceptionOrNull()
        assertTrue(error is IOException)
    }

    @Test
    fun `relanza la cancelación en vez de convertirla en failure`() {
        val repo = HealthRepositoryImpl { throw CancellationException("pantalla cerrada") }
        assertThrows<CancellationException> { runBlocking { repo.check() } }
    }
}
