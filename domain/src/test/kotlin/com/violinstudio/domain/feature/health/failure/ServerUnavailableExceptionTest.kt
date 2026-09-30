package com.violinstudio.domain.feature.health.failure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ServerUnavailableExceptionTest {
    @Test
    fun `guarda el estado y lo incluye en el mensaje`() {
        val error = ServerUnavailableException("degraded")
        assertEquals("degraded", error.status)
        assertEquals("El servidor respondió con estado 'degraded'", error.message)
    }
}
