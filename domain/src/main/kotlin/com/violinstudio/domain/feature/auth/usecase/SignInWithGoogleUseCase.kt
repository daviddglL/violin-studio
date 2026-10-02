package com.violinstudio.domain.feature.auth.usecase

import com.violinstudio.domain.feature.auth.model.AuthUser
import com.violinstudio.domain.feature.auth.model.GoogleIdToken
import com.violinstudio.domain.feature.auth.repository.AuthRepository
import javax.inject.Inject

class SignInWithGoogleUseCase @Inject constructor(private val auth: AuthRepository) {
    suspend operator fun invoke(token: GoogleIdToken): Result<AuthUser?> = throw NotImplementedError()
}
