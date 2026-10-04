package com.violinstudio.domain.feature.auth.usecase

import com.violinstudio.domain.feature.auth.failure.AuthFailure
import com.violinstudio.domain.feature.auth.repository.AuthRepository
import javax.inject.Inject

/**
 * Respuesta uniforme: no revela si el email existe, así que `UserNotFound` se trata como enviado; el resto de fallos
 * (`Network`, `TooManyRequests`, `ProviderUnavailable`...) se informan. Un formato inválido no llega a llamar.
 */
class SendPasswordResetUseCase @Inject constructor(private val auth: AuthRepository) {
    suspend operator fun invoke(email: String): Result<Unit> {
        val clean = email.trim()
        if (!isPlausibleEmail(clean)) return Result.failure(AuthFailure.InvalidEmail)
        val result = auth.sendPasswordReset(clean)
        // Solo "usuario inexistente" se oculta; sin runCatching para no tragar cancelaciones.
        return if (result.exceptionOrNull() is AuthFailure.UserNotFound) Result.success(Unit) else result
    }
}
