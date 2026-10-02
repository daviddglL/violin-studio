package com.violinstudio.domain.feature.consent.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.consent.repository.ConsentRepository
import javax.inject.Inject

class AcceptPolicyUseCase @Inject constructor(
    private val consent: ConsentRepository,
    private val auth: AuthRepository
) {
    suspend operator fun invoke(policyVersion: Int): Result<Unit> = throw NotImplementedError()
}
