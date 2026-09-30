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
    fun `desactivado no devuelve endpoint aunque haya host`() {
        assertNull(EmulatorConfig(enabled = false, host = "10.0.2.2").functions())
        assertNull(EmulatorConfig(enabled = false, host = "").functions())
    }

    @Test
    fun `activado con host vacío es un error de configuración`() {
        assertThrows<IllegalArgumentException> { EmulatorConfig(enabled = true, host = " ") }
    }
}
