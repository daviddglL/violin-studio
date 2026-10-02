package com.violinstudio.domain.feature.auth.usecase

import com.violinstudio.domain.feature.auth.failure.AuthFailure
import com.violinstudio.domain.feature.auth.repository.AuthRepository
import javax.inject.Inject

/**
 * Respuesta uniforme: no revela si el email existe, así que solo se informan `Network` y `TooManyRequests`; el resto
 * de fallos del proveedor se tratan como enviado. Un formato inválido no llega a llamar.
 */
class SendPasswordResetUseCase @Inject constructor(private val auth: AuthRepository) {
    suspend operator fun invoke(email: String): Result<Unit> {
        val clean = email.trim()
        if (!isPlausibleEmail(clean)) return Result.failure(AuthFailure.InvalidEmail)
        return auth.sendPasswordReset(clean).recoverCatching {
            if (it is AuthFailure.Network || it is AuthFailure.TooManyRequests) throw it
        }
    }
}
