package com.violinstudio.domain.feature.tuner.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.tuner.failure.TunerFailure
import com.violinstudio.domain.feature.tuner.model.MaxCents
import com.violinstudio.domain.feature.tuner.model.ReferencePitch
import com.violinstudio.domain.feature.tuner.model.TunerConfig
import com.violinstudio.domain.feature.tuner.model.TuningConfiguration
import com.violinstudio.domain.feature.tuner.repository.TunerConfigRepository
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** Aplica [transform] a la config del uid con sesion; los [TunerFailure] se devuelven en el `Result`. */
private suspend fun update(
    auth: AuthRepository,
    repo: TunerConfigRepository,
    transform: (TunerConfig) -> TunerConfig
): Result<Unit> {
    val uid = auth.authUser.first()?.uid ?: return Result.failure(IllegalStateException("Sin sesión"))
    return try {
        repo.update(uid, transform)
        Result.success(Unit)
    } catch (failure: TunerFailure) {
        Result.failure(failure)
    }
}

/** Config del usuario con sesion; sin sesion, los valores por defecto. */
class ObserveTunerConfigUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val repo: TunerConfigRepository
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<TunerConfig> =
        auth.authUser.flatMapLatest { user -> user?.let { repo.observe(it.uid) } ?: flowOf(TunerConfig()) }
}

/** Fija diapasón y tope de cents; deja de haber preset activo. */
class UpdateTunerConfigUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val repo: TunerConfigRepository
) {
    suspend operator fun invoke(referenceHz: Double, maxCents: Int): Result<Unit> = runCatching {
        ReferencePitch(referenceHz) to MaxCents(maxCents)
    }.fold(
        onSuccess = { (pitch, max) ->
            update(auth, repo) { it.copy(referencePitch = pitch, maxCents = max, selectedPresetId = null) }
        },
        onFailure = { Result.failure(it) }
    )
}

/** Crea (`id == null`) o edita un preset; devuelve su id. Máximo [TunerConfig.MAX_PRESETS]. */
class SaveTuningPresetUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val repo: TunerConfigRepository
) {
    suspend operator fun invoke(id: String?, label: String, referenceHz: Double, maxCents: Int): Result<String> {
        val presetId = id ?: UUID.randomUUID().toString()
        val preset = TuningConfiguration.create(presetId, label, referenceHz, maxCents)
            .getOrElse { return Result.failure(it) }
        return update(auth, repo) { config ->
            val presets = if (config.presets.any { it.id == presetId }) {
                config.presets.map { if (it.id == presetId) preset else it }
            } else {
                config.presets + preset
            }
            config.copy(presets = presets)
        }.map { presetId }
    }
}

/** Borra un preset; si era el activo vuelve a la configuración por defecto. */
class DeleteTuningPresetUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val repo: TunerConfigRepository
) {
    suspend operator fun invoke(id: String): Result<Unit> = update(auth, repo) { config ->
        val remaining = config.presets.filterNot { it.id == id }
        if (config.selectedPresetId == id) TunerConfig(presets = remaining) else config.copy(presets = remaining)
    }
}

/** Activa un preset: copia su diapasón y tope a la config activa. Un id desconocido no cambia nada. */
class SelectTuningPresetUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val repo: TunerConfigRepository
) {
    suspend operator fun invoke(id: String): Result<Unit> = update(auth, repo) { config ->
        config.presets.firstOrNull { it.id == id }?.let {
            config.copy(referencePitch = it.referencePitch, maxCents = it.maxCents, selectedPresetId = it.id)
        } ?: config
    }
}
