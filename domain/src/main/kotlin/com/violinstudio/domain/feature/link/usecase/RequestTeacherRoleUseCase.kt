package com.violinstudio.domain.feature.link.usecase

import com.violinstudio.domain.common.RetryBackoff
import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.auth.usecase.refreshClaims
import com.violinstudio.domain.feature.link.failure.LinkFailure
import com.violinstudio.domain.feature.link.repository.LinkRepository
import com.violinstudio.domain.feature.profile.model.TeacherRequestStatus
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Pide el alta de profesor: el servidor solo marca la solicitud y un administrador la aprueba. Si ya estaba aprobada
 * refresca los claims para que la sesion vea el rol `teacher` sin esperar a volver a primer plano.
 */
class RequestTeacherRoleUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val repo: LinkRepository
) {
    suspend operator fun invoke(): Result<TeacherRequestStatus> {
        auth.authUser.first() ?: return Result.failure(LinkFailure.NoSession)
        return repo.requestTeacherRole().onSuccess { status ->
            if (status == TeacherRequestStatus.APPROVED) auth.refreshClaims(RetryBackoff(), attempts = 1)
        }
    }
}
