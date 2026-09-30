package com.violinstudio.data.feature.health.utils.extensions

import com.violinstudio.data.feature.health.dto.HealthDto
import com.violinstudio.domain.feature.health.model.HealthInfo

fun HealthDto.toDomain(): HealthInfo = HealthInfo(status = status, version = version)
