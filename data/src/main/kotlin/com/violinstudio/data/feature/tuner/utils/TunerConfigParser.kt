package com.violinstudio.data.feature.tuner.utils

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.violinstudio.domain.feature.tuner.model.MaxCents
import com.violinstudio.domain.feature.tuner.model.ReferencePitch
import com.violinstudio.domain.feature.tuner.model.TunerConfig
import com.violinstudio.domain.feature.tuner.model.TuningConfiguration
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
internal data class TuningPresetDto(val id: String, val label: String, val referenceHz: Double, val maxCents: Int)

/** Tolerante: todo dato persistido pasa por las fábricas del dominio; lo inválido cae a defectos sin lanzar. */
object TunerConfigParser {
    private val presetsSerializer = ListSerializer(TuningPresetDto.serializer())

    fun read(prefs: Preferences, uid: String): TunerConfig {
        val presets = readPresets(prefs[UserKeys.presets(uid)])
        return TunerConfig(
            referencePitch = prefs[UserKeys.referenceHz(uid)]?.let { ReferencePitch.create(it).getOrNull() }
                ?: ReferencePitch.DEFAULT,
            maxCents = prefs[UserKeys.maxCents(uid)]?.let { MaxCents.create(it).getOrNull() } ?: MaxCents.DEFAULT,
            presets = presets,
            selectedPresetId = prefs[UserKeys.selectedPreset(uid)]?.takeIf { id -> presets.any { it.id == id } }
        )
    }

    fun write(prefs: MutablePreferences, uid: String, config: TunerConfig) {
        prefs[UserKeys.tunerVersion(uid)] = UserKeys.SCHEMA_VERSION
        prefs[UserKeys.referenceHz(uid)] = config.referencePitch.hz
        prefs[UserKeys.maxCents(uid)] = config.maxCents.value
        prefs[UserKeys.presets(uid)] = Json.encodeToString(
            presetsSerializer,
            config.presets.map { TuningPresetDto(it.id, it.label, it.referencePitch.hz, it.maxCents.value) }
        )
        val selected = config.selectedPresetId
        val key = UserKeys.selectedPreset(uid)
        if (selected != null) prefs[key] = selected else prefs.remove(key)
    }

    private fun readPresets(json: String?): List<TuningConfiguration> {
        val dtos = json?.let { runCatching { Json.decodeFromString(presetsSerializer, it) }.getOrNull() }.orEmpty()
        return dtos.mapNotNull { TuningConfiguration.create(it.id, it.label, it.referenceHz, it.maxCents).getOrNull() }
            .distinctBy { it.id }
            .take(TunerConfig.MAX_PRESETS)
    }
}
