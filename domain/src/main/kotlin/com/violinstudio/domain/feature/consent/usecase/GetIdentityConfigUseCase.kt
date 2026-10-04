package com.violinstudio.domain.feature.consent.usecase

import com.violinstudio.domain.feature.consent.model.IdentityConfig
import com.violinstudio.domain.feature.consent.repository.ConsentRepository
import javax.inject.Inject

/** El servidor reevalúa el consentimiento ante una nueva versión de política al responder. */
class GetIdentityConfigUseCase @Inject constructor(private val consent: ConsentRepository) {
    suspend operator fun invoke(): Result<IdentityConfig> = consent.identityConfig()
}
