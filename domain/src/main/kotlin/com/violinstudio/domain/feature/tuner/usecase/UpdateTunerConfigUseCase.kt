package com.violinstudio.domain.feature.tuner.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.tuner.model.MaxCents
import com.violinstudio.domain.feature.tuner.model.ReferencePitch
import com.violinstudio.domain.feature.tuner.repository.TunerConfigRepository
import javax.inject.Inject

/** Fija diapasón y tope de cents; deja de haber preset activo. */
class UpdateTunerConfigUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val repo: TunerConfigRepository
) {
    suspend operator fun invoke(referenceHz: Double, maxCents: Int): Result<Unit> {
        val pitch = ReferencePitch.create(referenceHz).getOrElse { return Result.failure(it) }
        val max = MaxCents.create(maxCents).getOrElse { return Result.failure(it) }
        return updateTunerConfig(auth, repo) {
            it.copy(referencePitch = pitch, maxCents = max, selectedPresetId = null)
        }
    }
}
