package com.violinstudio.domain.feature.auth.usecase

import com.violinstudio.domain.feature.auth.model.GoogleIdToken
import com.violinstudio.domain.feature.auth.repository.AuthRepository
import javax.inject.Inject

class ReauthenticateUseCase @Inject constructor(private val auth: AuthRepository) {
    suspend operator fun invoke(password: String): Result<Unit> = throw NotImplementedError()

    suspend operator fun invoke(token: GoogleIdToken): Result<Unit> = throw NotImplementedError()
}
