package com.violinstudio.domain.feature.link.usecase

import com.violinstudio.domain.common.RetryBackoff
import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.auth.usecase.refreshClaims
import com.violinstudio.domain.feature.link.failure.LinkFailure
import com.violinstudio.domain.feature.link.repository.LinkRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/** Solo una de las partes puede desvincular (el servidor lo vuelve a comprobar). Refresca claims al terminar. */
class UnlinkUseCase @Inject constructor(private val auth: AuthRepository, private val repo: LinkRepository) {
    suspend operator fun invoke(teacherUid: String, studentUid: String): Result<Unit> {
        val uid = auth.authUser.first()?.uid ?: return Result.failure(LinkFailure.NoSession)
        if (uid != teacherUid && uid != studentUid) return Result.failure(LinkFailure.PermissionDenied)
        return repo.unlink(teacherUid, studentUid).onSuccess { auth.refreshClaims(RetryBackoff(), attempts = 1) }
    }
}
