package com.violinstudio.domain.feature.consent.usecase

import com.violinstudio.domain.common.RetryBackoff
import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.auth.usecase.refreshClaims
import com.violinstudio.domain.feature.consent.repository.ConsentRepository
import javax.inject.Inject

/**
 * Revoca y refresca claims en la medida de lo posible: la revocación ya está hecha en el servidor, así que un fallo
 * del refresco no la invalida (el estado de sesión sale del perfil, no del claim). Un solo intento: no bloquea la UI.
 */
class RevokeConsentUseCase @Inject constructor(
    private val consent: ConsentRepository,
    private val auth: AuthRepository
) {
    suspend operator fun invoke(): Result<Unit> {
        consent.revokeConsent().onFailure { return Result.failure(it) }
        auth.refreshClaims(RetryBackoff(), attempts = 1)
        return Result.success(Unit)
    }
}
