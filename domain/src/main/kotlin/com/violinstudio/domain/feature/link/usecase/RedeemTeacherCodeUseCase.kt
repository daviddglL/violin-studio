package com.violinstudio.domain.feature.link.usecase

import com.violinstudio.domain.feature.link.failure.LinkFailure
import com.violinstudio.domain.feature.link.model.TeacherCodeInput
import com.violinstudio.domain.feature.link.repository.LinkRepository
import javax.inject.Inject

/** Normaliza y valida el código antes de tocar la red (REQ-LNK-03). */
class RedeemTeacherCodeUseCase @Inject constructor(private val repo: LinkRepository) {
    /** Devuelve el uid del profesor vinculado. */
    suspend operator fun invoke(rawCode: String, ackVersion: Int): Result<String> {
        val code = TeacherCodeInput.normalize(rawCode) ?: return Result.failure(LinkFailure.InvalidCode)
        return repo.redeem(code, ackVersion)
    }
}
