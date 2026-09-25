package com.violinstudio.core.data.health

import com.violinstudio.core.model.HealthInfo
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HealthRepositoryTest {
    @Test
    fun `devuelve success cuando el servidor responde ok`() = runTest {
        val repo = HealthRepository { HealthInfo("ok", "0.1.0") }
        assertEquals(Result.success(HealthInfo("ok", "0.1.0")), repo.check())
    }

    @Test
    fun `devuelve ServerUnavailableException cuando status no es ok`() = runTest {
        val error = HealthRepository { HealthInfo("degraded", "0.1.0") }.check().exceptionOrNull()
        assertTrue(error is ServerUnavailableException)
        assertEquals("degraded", (error as ServerUnavailableException).status)
    }

    @Test
    fun `convierte los errores de red en failure`() = runTest {
        val error = HealthRepository { throw IOException("sin red") }.check().exceptionOrNull()
        assertTrue(error is IOException)
    }

    @Test
    fun `relanza la cancelación en vez de convertirla en failure`() {
        val repo = HealthRepository { throw CancellationException("pantalla cerrada") }
        assertThrows<CancellationException> { runBlocking { repo.check() } }
    }
}
