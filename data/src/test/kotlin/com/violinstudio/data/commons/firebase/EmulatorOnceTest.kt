package com.violinstudio.data.commons.firebase

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EmulatorOnceTest {
    @Test
    fun `aplicar dos veces el mismo servicio solo ejecuta la configuracion una vez`() {
        val once = EmulatorOnce()
        var calls = 0
        once.apply("auth") { calls++ }
        once.apply("auth") { calls++ }
        assertEquals(1, calls)
    }

    @Test
    fun `cada servicio se configura una vez por separado`() {
        val once = EmulatorOnce()
        val seen = mutableListOf<String>()
        once.apply("auth") { seen += "auth" }
        once.apply("firestore") { seen += "firestore" }
        once.apply("auth") { seen += "auth" }
        assertEquals(listOf("auth", "firestore"), seen)
    }
}
