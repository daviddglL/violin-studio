package com.violinstudio.domain.feature.tuner.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.tuner.model.TunerConfig
import com.violinstudio.domain.feature.tuner.model.TuningConfiguration
import com.violinstudio.domain.feature.tuner.repository.TunerConfigRepository
import java.util.UUID
import javax.inject.Inject

/**
 * Crea (`id == null`) o edita un preset; devuelve su id. Máximo [TunerConfig.MAX_PRESETS]. Si se edita el preset
 * activo, la config activa toma sus nuevos valores.
 */
class SaveTuningPresetUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val repo: TunerConfigRepository
) {
    suspend operator fun invoke(id: String?, label: String, referenceHz: Double, maxCents: Int): Result<String> {
        val presetId = id ?: UUID.randomUUID().toString()
        val preset = TuningConfiguration.create(presetId, label, referenceHz, maxCents)
            .getOrElse { return Result.failure(it) }
        return updateTunerConfig(auth, repo) { config ->
            val exists = config.presets.any { it.id == presetId }
            val presets =
                if (exists) config.presets.map { if (it.id == presetId) preset else it } else config.presets + preset
            val active = config.selectedPresetId == presetId
            config.copy(
                presets = presets,
                referencePitch = if (active) preset.referencePitch else config.referencePitch,
                maxCents = if (active) preset.maxCents else config.maxCents
            )
        }.map { presetId }
    }
}
