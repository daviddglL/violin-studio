package com.violinstudio.domain.feature.tuner.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.tuner.model.TunerConfig
import com.violinstudio.domain.feature.tuner.repository.TunerConfigRepository
import javax.inject.Inject

/** Borra un preset; si era el activo vuelve a la configuración por defecto. */
class DeleteTuningPresetUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val repo: TunerConfigRepository
) {
    suspend operator fun invoke(id: String): Result<Unit> = updateTunerConfig(auth, repo) { config ->
        val remaining = config.presets.filterNot { it.id == id }
        if (config.selectedPresetId == id) TunerConfig(presets = remaining) else config.copy(presets = remaining)
    }
}
