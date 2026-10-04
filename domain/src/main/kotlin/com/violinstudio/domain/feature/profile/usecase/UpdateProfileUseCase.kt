package com.violinstudio.domain.feature.profile.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.profile.failure.ProfileFailure
import com.violinstudio.domain.feature.profile.model.EditableProfile
import com.violinstudio.domain.feature.profile.repository.ProfileRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first

class UpdateProfileUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val profile: ProfileRepository
) {
    suspend operator fun invoke(edit: EditableProfile): Result<Unit> {
        val uid = auth.authUser.first()?.uid ?: return Result.failure(ProfileFailure.NoProfile)
        return profile.update(uid, edit)
    }
}
