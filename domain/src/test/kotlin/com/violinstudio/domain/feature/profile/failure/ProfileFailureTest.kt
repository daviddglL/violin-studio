package com.violinstudio.domain.feature.profile.failure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProfileFailureTest {
    private fun label(failure: ProfileFailure): String = when (failure) {
        ProfileFailure.UnderageNotAllowed -> "underage"
        ProfileFailure.InvalidBirthDate -> "birth"
        ProfileFailure.InvalidInput -> "input"
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
            ProfileFailure.InvalidInput,
            ProfileFailure.NoProfile,
            ProfileFailure.EmailNotVerified,
            ProfileFailure.Network,
            ProfileFailure.Unknown()
        )
        assertEquals(7, all.map(::label).toSet().size)
    }
}
