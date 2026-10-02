package com.violinstudio.domain.feature.auth.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import javax.inject.Inject

class SendPasswordResetUseCase @Inject constructor(private val auth: AuthRepository) {
    suspend operator fun invoke(email: String): Result<Unit> = throw NotImplementedError()
}
