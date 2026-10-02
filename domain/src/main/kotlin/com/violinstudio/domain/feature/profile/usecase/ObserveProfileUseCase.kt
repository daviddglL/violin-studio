package com.violinstudio.domain.feature.profile.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.profile.model.UserProfile
import com.violinstudio.domain.feature.profile.repository.ProfileRepository
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** Perfil del usuario con sesión; `null` sin sesión o mientras no exista `users/{uid}`. */
class ObserveProfileUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val profile: ProfileRepository
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<UserProfile?> =
        auth.authUser.flatMapLatest { user -> if (user == null) flowOf(null) else profile.observe(user.uid) }
}
