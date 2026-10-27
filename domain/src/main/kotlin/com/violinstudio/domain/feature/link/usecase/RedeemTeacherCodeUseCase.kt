package com.violinstudio.domain.feature.link.usecase

import com.violinstudio.domain.common.RetryBackoff
import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.auth.usecase.refreshClaims
import com.violinstudio.domain.feature.link.failure.LinkFailure
import com.violinstudio.domain.feature.link.model.TeacherCodeInput
import com.violinstudio.domain.feature.link.repository.LinkRepository
import javax.inject.Inject

/**
 * Normaliza y valida el código antes de tocar la red (REQ-LNK-03). Tras canjear refresca los claims en la medida
 * de lo posible (un intento): el canje ya está hecho en el servidor y el rol de sesión sale del perfil.
 */
class RedeemTeacherCodeUseCase @Inject constructor(private val repo: LinkRepository, private val auth: AuthRepository) {
    /** Devuelve el uid del profesor vinculado. */
    suspend operator fun invoke(rawCode: String, ackVersion: Int): Result<String> {
        val code = TeacherCodeInput.normalize(rawCode) ?: return Result.failure(LinkFailure.InvalidCode)
        return repo.redeem(code, ackVersion).onSuccess { auth.refreshClaims(RetryBackoff(), attempts = 1) }
    }
}
