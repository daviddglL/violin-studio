package com.violinstudio.core.data.health

import com.violinstudio.core.model.HealthInfo

fun interface HealthRemoteSource {
    suspend fun fetchHealth(): HealthInfo
}
