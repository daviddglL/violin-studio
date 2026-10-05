package com.violinstudio.domain.feature.tuner.model

import com.violinstudio.domain.feature.tuner.failure.TunerFailure

data class TuningConfiguration(
    val id: String,
    val label: String,
    val referencePitch: ReferencePitch,
    val maxCents: MaxCents
) {
    init {
        if (label.isBlank() || label.length > MAX_LABEL) throw TunerFailure.InvalidConfig
    }

    companion object {
        const val MAX_LABEL = 30
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
