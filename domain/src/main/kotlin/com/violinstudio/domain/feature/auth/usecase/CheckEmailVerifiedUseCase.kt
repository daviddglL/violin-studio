package com.violinstudio.domain.feature.auth.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import javax.inject.Inject

/** Recarga el usuario y fuerza token nuevo; `true` si el email ya consta como verificado. */
class CheckEmailVerifiedUseCase @Inject constructor(private val auth: AuthRepository) {
    suspend operator fun invoke(): Result<Boolean> = auth.reloadAndRefreshToken().map { it.emailVerified }
}
