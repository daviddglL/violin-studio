package com.violinstudio.data.feature.health.datasource

import com.violinstudio.data.feature.health.dto.HealthDto

fun interface HealthRemoteDataSource {
    suspend fun fetchHealth(): HealthDto
}
