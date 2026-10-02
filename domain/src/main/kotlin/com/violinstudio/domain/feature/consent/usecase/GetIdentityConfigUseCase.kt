package com.violinstudio.domain.feature.consent.usecase

import com.violinstudio.domain.feature.consent.model.IdentityConfig
import com.violinstudio.domain.feature.consent.repository.ConsentRepository
import javax.inject.Inject

class GetIdentityConfigUseCase @Inject constructor(private val consent: ConsentRepository) {
    suspend operator fun invoke(): Result<IdentityConfig> = throw NotImplementedError()
}
