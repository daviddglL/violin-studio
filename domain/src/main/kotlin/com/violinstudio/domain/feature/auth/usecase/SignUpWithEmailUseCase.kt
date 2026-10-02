package com.violinstudio.domain.feature.auth.usecase

import com.violinstudio.domain.feature.auth.model.AuthUser
import com.violinstudio.domain.feature.auth.repository.AuthRepository
import javax.inject.Inject

/**
 * Registra y envía el email de verificación. Si el envío falla, el registro sigue siendo correcto: la pantalla de
 * verificación permite reenviarlo. `EmailAlreadyInUse` y `WeakPassword` no envían nada ni cambian la sesión.
 */
class SignUpWithEmailUseCase @Inject constructor(private val auth: AuthRepository) {
    suspend operator fun invoke(email: String, password: String): Result<AuthUser> =
        auth.signUpWithEmail(email.trim(), password).onSuccess { auth.sendEmailVerification() }
}
