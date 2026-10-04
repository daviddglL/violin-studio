package com.violinstudio.data.feature.auth.utils

import com.violinstudio.data.feature.auth.dto.AuthUserDto
import com.violinstudio.data.feature.auth.dto.ClaimsDto
import com.violinstudio.data.feature.auth.utils.extensions.toDomain
import com.violinstudio.domain.feature.auth.model.AuthProvider
import com.violinstudio.domain.feature.profile.model.Role
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AuthUserMapperTest {
    private fun dto(verified: Boolean, vararg providers: String) =
        AuthUserDto("u1", "a@b.co", verified, providers.toList())

    @Test
    fun `google se considera verificado aunque el SDK diga false`() {
        val user = dto(false, "firebase", "google.com").toDomain()
        assertEquals(setOf(AuthProvider.GOOGLE), user.providers)
        assertEquals(true, user.emailVerified)
    }

    @Test
    fun `password conserva emailVerified del SDK`() {
        val unverified = dto(false, "password").toDomain()
        assertEquals(setOf(AuthProvider.PASSWORD), unverified.providers)
        assertFalse(unverified.emailVerified)
        assertEquals(true, dto(true, "password").toDomain().emailVerified)
    }

    @Test
    fun `proveedores desconocidos se ignoran y se conservan uid y email`() {
        val user = dto(false, "firebase", "facebook.com").toDomain()
        assertEquals(emptySet<AuthProvider>(), user.providers)
        assertEquals("u1", user.uid)
        assertEquals("a@b.co", user.email)
    }

    @Test
    fun `claims con role conocido, desconocido y ausente`() {
        assertEquals(Role.STUDENT, ClaimsDto("student", true).toDomain().role)
        assertEquals(true, ClaimsDto("student", true).toDomain().consentOk)
        assertNull(ClaimsDto("astronaut", false).toDomain().role)
        assertNull(ClaimsDto(null, false).toDomain().role)
    }
}
