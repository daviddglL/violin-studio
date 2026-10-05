package com.violinstudio.domain.feature.tuner.model

import com.violinstudio.domain.feature.tuner.failure.TunerFailure
import com.violinstudio.domain.feature.tuner.failure.TunerField

data class TuningConfiguration(
    val id: String,
    val label: String,
    val referencePitch: ReferencePitch,
    val maxCents: MaxCents
) {
    init {
        if (id.isBlank()) throw TunerFailure.InvalidConfig(TunerField.ID)
        if (label.isBlank() || label.length > MAX_LABEL) throw TunerFailure.InvalidConfig(TunerField.LABEL)
    }

    companion object {
        const val MAX_LABEL = 30

        /** Valida y construye desde valores crudos (p. ej. datos persistidos). */
        fun create(id: String, label: String, referenceHz: Double, maxCents: Int): Result<TuningConfiguration> =
            runCatching {
                TuningConfiguration(id, label, ReferencePitch(referenceHz), MaxCents(maxCents))
            }
    }
}

data class TunerConfig(
    val referencePitch: ReferencePitch = ReferencePitch.DEFAULT,
    val maxCents: MaxCents = MaxCents.DEFAULT,
    val presets: List<TuningConfiguration> = emptyList(),
    val selectedPresetId: String? = null
) {
    init {
        if (presets.size > MAX_PRESETS) throw TunerFailure.PresetLimitReached
    }

    companion object {
        const val MAX_PRESETS = 20
    }
}
