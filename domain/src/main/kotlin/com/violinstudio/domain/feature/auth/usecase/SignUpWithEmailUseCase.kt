package com.violinstudio.domain.feature.auth.usecase

import com.violinstudio.domain.feature.auth.failure.AuthFailure
import com.violinstudio.domain.feature.auth.model.AuthUser
import com.violinstudio.domain.feature.auth.repository.AuthRepository
import javax.inject.Inject

/**
 * Registra y envía el email de verificación. Si el envío falla, el registro sigue siendo correcto: la pantalla de
 * verificación permite reenviarlo. `EmailAlreadyInUse` y `WeakPassword` no envían nada ni cambian la sesión.
 * Un formato de email inválido no llega a llamar.
 */
class SignUpWithEmailUseCase @Inject constructor(private val auth: AuthRepository) {
    suspend operator fun invoke(email: String, password: String): Result<AuthUser> {
        val clean = email.trim()
        if (!isPlausibleEmail(clean)) return Result.failure(AuthFailure.InvalidEmail)
        return auth.signUpWithEmail(clean, password).onSuccess { auth.sendEmailVerification() }
    }
}
