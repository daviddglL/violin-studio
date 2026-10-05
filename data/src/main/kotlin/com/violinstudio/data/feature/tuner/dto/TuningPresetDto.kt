package com.violinstudio.data.feature.tuner.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class TuningPresetDto(val id: String, val label: String, val referenceHz: Double, val maxCents: Int)
