package com.violinstudio.domain.feature.practice.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.practice.failure.PracticeFailure
import com.violinstudio.domain.feature.practice.repository.RunningSessionStore
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/** Descarta la sesión en curso sin guardar nada. */
class DiscardRunningSessionUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val store: RunningSessionStore
) {
    suspend operator fun invoke(): Result<Unit> {
        val uid = auth.authUser.first()?.uid ?: return Result.failure(PracticeFailure.NoSession)
        store.clear(uid)
        return Result.success(Unit)
    }
}
