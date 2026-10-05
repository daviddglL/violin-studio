package com.violinstudio.data.feature.tuner.utils

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.violinstudio.data.feature.tuner.dto.TuningPresetDto
import com.violinstudio.domain.feature.tuner.model.MaxCents
import com.violinstudio.domain.feature.tuner.model.ReferencePitch
import com.violinstudio.domain.feature.tuner.model.TunerConfig
import com.violinstudio.domain.feature.tuner.model.TuningConfiguration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray

/**
 * Tolerante: todo dato persistido pasa por las fábricas del dominio y lo inválido (valor fuera de rango, clave con
 * tipo equivocado, JSON roto, preset malo) cae a defectos o se descarta sin lanzar.
 *
 * Política de esquema: si `tuner.v` es posterior a [UserKeys.SCHEMA_VERSION] (datos de una app más nueva) se lee
 * como defectos y el repositorio se niega a escribir, para no degradar esos datos.
 */
object TunerConfigParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(prefs: Preferences, uid: String): TunerConfig {
        if (isNewerSchema(prefs, uid)) return TunerConfig()
        val presets = readPresets(prefs.safeGet(UserKeys.presets(uid)))
        return TunerConfig(
            referencePitch = prefs.safeGet(UserKeys.referenceHz(uid))?.let { ReferencePitch.create(it).getOrNull() }
                ?: ReferencePitch.DEFAULT,
            maxCents = prefs.safeGet(UserKeys.maxCents(uid))?.let { MaxCents.create(it).getOrNull() }
                ?: MaxCents.DEFAULT,
            presets = presets,
            selectedPresetId = prefs.safeGet(UserKeys.selectedPreset(uid))?.takeIf { id -> presets.any { it.id == id } }
        )
    }

    fun isNewerSchema(prefs: Preferences, uid: String): Boolean =
        (prefs.safeGet(UserKeys.tunerVersion(uid)) ?: 0) > UserKeys.SCHEMA_VERSION

    fun write(prefs: MutablePreferences, uid: String, config: TunerConfig) {
        prefs[UserKeys.tunerVersion(uid)] = UserKeys.SCHEMA_VERSION
        prefs[UserKeys.referenceHz(uid)] = config.referencePitch.hz
        prefs[UserKeys.maxCents(uid)] = config.maxCents.value
        prefs[UserKeys.presets(uid)] = json.encodeToString(
            config.presets.map { TuningPresetDto(it.id, it.label, it.referencePitch.hz, it.maxCents.value) }
        )
        val selected = config.selectedPresetId
        val key = UserKeys.selectedPreset(uid)
        if (selected != null) prefs[key] = selected else prefs.remove(key)
    }

    /** `Preferences.Key` se compara solo por nombre: un tipo distinto del esperado lanzaría `ClassCastException`. */
    private inline fun <reified T : Any> Preferences.safeGet(key: Preferences.Key<T>): T? =
        asMap().entries.firstOrNull { it.key.name == key.name }?.value as? T

    private fun readPresets(raw: String?): List<TuningConfiguration> {
        val elements = raw?.let { runCatching { json.parseToJsonElement(it).jsonArray }.getOrNull() }.orEmpty()
        return elements.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(TuningPresetDto.serializer(), element) }.getOrNull()
                ?.let { TuningConfiguration.create(it.id, it.label, it.referenceHz, it.maxCents).getOrNull() }
        }.distinctBy { it.id }.take(TunerConfig.MAX_PRESETS)
    }
}
