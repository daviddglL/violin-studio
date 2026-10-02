package com.violinstudio.domain.feature.auth.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AuthModelTest {
    @Test
    fun `AuthUser indica si usa contrasena`() {
        val password = AuthUser("u1", "a@b.com", false, setOf(AuthProvider.PASSWORD))
        val google = AuthUser("u2", "a@b.com", true, setOf(AuthProvider.GOOGLE))
        assertTrue(password.usesPassword)
        assertFalse(google.usesPassword)
    }

    @Test
    fun `SessionClaims admite rol desconocido`() {
        assertEquals(null, SessionClaims(role = null, consentOk = false).role)
    }

    @Test
    fun `GoogleIdToken no admite valor vacio y no se filtra en toString`() {
        assertThrows<IllegalArgumentException> { GoogleIdToken(" ") }
        assertFalse(GoogleIdToken("secreto").toString().contains("secreto"))
    }

    @Test
    fun `toString de AuthUser no filtra el email`() {
        val user = AuthUser("u1", "ana@secreto.com", true, setOf(AuthProvider.PASSWORD))
        assertFalse(user.toString().contains("ana@secreto.com"))
    }
}
