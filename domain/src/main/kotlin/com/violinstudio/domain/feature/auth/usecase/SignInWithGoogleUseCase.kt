package com.violinstudio.domain.feature.auth.usecase

import com.violinstudio.domain.feature.auth.failure.AuthFailure
import com.violinstudio.domain.feature.auth.model.AuthUser
import com.violinstudio.domain.feature.auth.model.GoogleIdToken
import com.violinstudio.domain.feature.auth.repository.AuthRepository
import javax.inject.Inject

/** Éxito con `null` si el usuario cancela: cancelar no es un estado de error. */
class SignInWithGoogleUseCase @Inject constructor(private val auth: AuthRepository) {
    suspend operator fun invoke(token: GoogleIdToken): Result<AuthUser?> = auth.signInWithGoogle(token).fold(
        onSuccess = { Result.success(it) },
        onFailure = { if (it is AuthFailure.Cancelled) Result.success(null) else Result.failure(it) }
    )
}
