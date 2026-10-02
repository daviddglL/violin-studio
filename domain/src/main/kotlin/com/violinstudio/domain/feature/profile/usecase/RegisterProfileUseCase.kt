package com.violinstudio.domain.feature.profile.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.profile.model.ProfileRegistration
import com.violinstudio.domain.feature.profile.repository.ProfileRepository
import javax.inject.Inject

class RegisterProfileUseCase @Inject constructor(
    private val profile: ProfileRepository,
    private val auth: AuthRepository
) {
    suspend operator fun invoke(registration: ProfileRegistration): Result<Unit> = throw NotImplementedError()
}
