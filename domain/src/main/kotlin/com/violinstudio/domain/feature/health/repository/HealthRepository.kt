package com.violinstudio.domain.feature.health.repository

import com.violinstudio.domain.feature.health.model.HealthInfo

fun interface HealthRepository {
    /** Nunca lanza salvo cancelación: los fallos llegan como [Result.failure]. */
    suspend fun check(): Result<HealthInfo>
}
