package com.violinstudio.domain.feature.auth.usecase

import com.violinstudio.domain.feature.auth.model.AuthUser
import com.violinstudio.domain.feature.auth.repository.AuthRepository
import javax.inject.Inject

/** Los fallos (`InvalidCredentials`, `TooManyRequests`...) se devuelven tal cual; la sesión solo cambia por `authUser`. */
class SignInWithEmailUseCase @Inject constructor(private val auth: AuthRepository) {
    suspend operator fun invoke(email: String, password: String): Result<AuthUser> =
        auth.signInWithEmail(email.trim(), password)
}
