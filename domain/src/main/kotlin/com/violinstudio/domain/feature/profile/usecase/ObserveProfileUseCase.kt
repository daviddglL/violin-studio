package com.violinstudio.domain.feature.profile.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.profile.model.UserProfile
import com.violinstudio.domain.feature.profile.repository.ProfileRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

class ObserveProfileUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val profile: ProfileRepository
) {
    operator fun invoke(): Flow<UserProfile?> = throw NotImplementedError()
}
