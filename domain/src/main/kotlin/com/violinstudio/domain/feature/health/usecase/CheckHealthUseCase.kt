package com.violinstudio.domain.feature.health.usecase

import com.violinstudio.domain.feature.health.model.HealthInfo
import com.violinstudio.domain.feature.health.repository.HealthRepository
import javax.inject.Inject

class CheckHealthUseCase @Inject constructor(private val repository: HealthRepository) {
    suspend operator fun invoke(): Result<HealthInfo> = repository.check()
}
