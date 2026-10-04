package com.violinstudio.domain.feature.profile.failure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProfileFailureTest {
    private fun label(failure: ProfileFailure): String = when (failure) {
        ProfileFailure.UnderageNotAllowed -> "underage"
        ProfileFailure.InvalidBirthDate -> "birth"
        is ProfileFailure.InvalidInput -> "input"
        ProfileFailure.NoProfile -> "no-profile"
        ProfileFailure.EmailNotVerified -> "email"
        ProfileFailure.Network -> "network"
        is ProfileFailure.Unknown -> "unknown"
    }

    @Test
    fun `cubre los fallos de perfil incluido UnderageNotAllowed`() {
        val all = listOf(
            ProfileFailure.UnderageNotAllowed,
            ProfileFailure.InvalidBirthDate,
            ProfileFailure.InvalidInput(ProfileField.LOCALE),
            ProfileFailure.NoProfile,
            ProfileFailure.EmailNotVerified,
            ProfileFailure.Network,
            ProfileFailure.Unknown()
        )
        assertEquals(7, all.map(::label).toSet().size)
    }

    @Test
    fun `el campo del servidor se resuelve por su nombre de red`() {
        assertEquals(ProfileField.DISPLAY_NAME, ProfileField.fromWire("displayName"))
        assertEquals(ProfileField.BIRTH_DATE, ProfileField.fromWire("birthDate"))
        assertEquals(ProfileField.INSTRUMENT, ProfileField.fromWire("instrument"))
        assertEquals(ProfileField.LOCALE, ProfileField.fromWire("locale"))
        assertEquals(null, ProfileField.fromWire("role"))
        assertEquals(null, ProfileField.fromWire(null))
    }
}
