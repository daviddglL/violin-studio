package com.violinstudio.domain.feature.profile.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.profile.model.EditableProfile
import com.violinstudio.domain.feature.profile.repository.ProfileRepository
import javax.inject.Inject

class UpdateProfileUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val profile: ProfileRepository
) {
    suspend operator fun invoke(edit: EditableProfile): Result<Unit> = throw NotImplementedError()
}
