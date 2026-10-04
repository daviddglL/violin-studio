package com.violinstudio.data.feature.auth.datasource.firebase

import com.violinstudio.data.feature.auth.dto.AuthUserDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ClearedSessionMaskTest {
    private fun user(uid: String) = AuthUserDto(uid, "$uid@example.test", true, listOf("password"))

    @Test
    fun `el usuario limpiado queda oculto`() {
        assertNull(maskCleared(user("u1"), clearedUid = "u1"))
    }

    @Test
    fun `un usuario distinto del limpiado nunca se oculta (no se pega a la siguiente sesion)`() {
        assertEquals(user("u2"), maskCleared(user("u2"), clearedUid = "u1"))
    }

    @Test
    fun `sin limpieza pasa el usuario y sin usuario sigue nulo`() {
        assertEquals(user("u1"), maskCleared(user("u1"), clearedUid = null))
        assertNull(maskCleared(null, clearedUid = "u1"))
    }
}
