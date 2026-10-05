package com.violinstudio.domain.feature.tuner.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.tuner.failure.TunerFailure
import com.violinstudio.domain.feature.tuner.repository.TunerConfigRepository
import javax.inject.Inject

/** Activa un preset: copia su diapasón y tope a la config activa. Un id desconocido falla con `PresetNotFound`. */
class SelectTuningPresetUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val repo: TunerConfigRepository
) {
    suspend operator fun invoke(id: String): Result<Unit> = updateTunerConfig(auth, repo) { config ->
        val preset = config.presets.firstOrNull { it.id == id } ?: throw TunerFailure.PresetNotFound
        config.copy(referencePitch = preset.referencePitch, maxCents = preset.maxCents, selectedPresetId = preset.id)
    }
}
