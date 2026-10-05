package com.violinstudio.domain.feature.tuner.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.tuner.failure.TunerFailure
import com.violinstudio.domain.feature.tuner.model.TunerConfig
import com.violinstudio.domain.feature.tuner.repository.TunerConfigRepository
import kotlinx.coroutines.flow.first

/** Aplica [transform] a la config del uid con sesión; los [TunerFailure] (incl. `NoSession`) van en el `Result`. */
internal suspend fun updateTunerConfig(
    auth: AuthRepository,
    repo: TunerConfigRepository,
    transform: (TunerConfig) -> TunerConfig
): Result<Unit> {
    val uid = auth.authUser.first()?.uid ?: return Result.failure(TunerFailure.NoSession)
    return try {
        repo.update(uid, transform)
        Result.success(Unit)
    } catch (failure: TunerFailure) {
        Result.failure(failure)
    }
}
