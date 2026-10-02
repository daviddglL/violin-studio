package com.violinstudio.domain.feature.auth.usecase

import com.violinstudio.domain.feature.auth.model.GoogleIdToken
import com.violinstudio.domain.feature.auth.repository.AuthRepository
import javax.inject.Inject

/** Reautenticación reciente (requisito de `deleteAccount`): con contraseña o con token de Google. */
class ReauthenticateUseCase @Inject constructor(private val auth: AuthRepository) {
    suspend operator fun invoke(password: String): Result<Unit> = auth.reauthenticateWithPassword(password)

    suspend operator fun invoke(token: GoogleIdToken): Result<Unit> = auth.reauthenticateWithGoogle(token)
}
