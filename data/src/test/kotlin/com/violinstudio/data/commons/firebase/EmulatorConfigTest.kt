package com.violinstudio.data.commons.firebase

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EmulatorConfigTest {
    @Test
    fun `activado devuelve el endpoint de Functions en el puerto 5001`() {
        assertEquals(
            EmulatorEndpoint("10.0.2.2", 5001),
            EmulatorConfig(enabled = true, host = "10.0.2.2").functions()
        )
    }

    @Test
    fun `activado devuelve los endpoints de Auth 9099 y Firestore 8080`() {
        val config = EmulatorConfig(enabled = true, host = "10.0.2.2")
        assertEquals(EmulatorEndpoint("10.0.2.2", 9099), config.auth())
        assertEquals(EmulatorEndpoint("10.0.2.2", 8080), config.firestore())
    }

    @Test
    fun `desactivado no devuelve endpoint aunque haya host`() {
        assertNull(EmulatorConfig(enabled = false, host = "10.0.2.2").functions())
        assertNull(EmulatorConfig(enabled = false, host = "").functions())
        assertNull(EmulatorConfig(enabled = false, host = "10.0.2.2").auth())
        assertNull(EmulatorConfig(enabled = false, host = "10.0.2.2").firestore())
    }

    @Test
    fun `activado con host vacío es un error de configuración`() {
        assertThrows<IllegalArgumentException> { EmulatorConfig(enabled = true, host = " ") }
    }
}
