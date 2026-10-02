package com.violinstudio.domain.feature.consent.usecase

import com.violinstudio.domain.feature.consent.model.GuardianRequestReceipt
import com.violinstudio.domain.feature.consent.repository.ConsentRepository
import javax.inject.Inject

class RequestGuardianConsentUseCase @Inject constructor(private val consent: ConsentRepository) {
    suspend operator fun invoke(guardianEmail: String): Result<GuardianRequestReceipt> = throw NotImplementedError()
}
