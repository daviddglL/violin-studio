package com.violinstudio.domain.feature.profile.usecase

import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.FakeProfileRepository
import com.violinstudio.domain.feature.profile.failure.ProfileFailure
import com.violinstudio.domain.feature.profile.model.EditableProfile
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.verifiedUser
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UpdateProfileUseCaseTest {
    private val auth = FakeAuthRepository()
    private val repo = FakeProfileRepository()
    private val edit = EditableProfile.create("Ana", Instrument.CELLO, "es").getOrThrow()
    private val useCase = UpdateProfileUseCase(auth, repo)

    @Test
    fun `actualiza el perfil del usuario con sesion`() = runTest {
        auth.user.value = verifiedUser
        assertEquals(Result.success(Unit), useCase(edit))
        assertEquals(listOf("update:u1"), repo.calls)
        assertEquals(edit, repo.lastUpdate)
    }

    @Test
    fun `sin sesion falla con NoProfile sin llamar al repositorio`() = runTest {
        assertEquals(ProfileFailure.NoProfile, useCase(edit).exceptionOrNull())
        assertEquals(emptyList<String>(), repo.calls)
    }

    @Test
    fun `propaga el fallo del repositorio`() = runTest {
        auth.user.value = verifiedUser
        repo.updateResult = Result.failure(ProfileFailure.Network)
        assertEquals(ProfileFailure.Network, useCase(edit).exceptionOrNull())
    }
}
