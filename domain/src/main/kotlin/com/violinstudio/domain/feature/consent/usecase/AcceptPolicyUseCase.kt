package com.violinstudio.domain.feature.consent.usecase

import com.violinstudio.domain.feature.auth.failure.AuthFailure
import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.auth.usecase.refreshClaims
import com.violinstudio.domain.feature.consent.failure.ConsentFailure
import com.violinstudio.domain.feature.consent.repository.ConsentRepository
import javax.inject.Inject

/** Registra el consentimiento de la versión dada y fuerza el refresco de claims (`consentOk`). */
class AcceptPolicyUseCase @Inject constructor(
    private val consent: ConsentRepository,
    private val auth: AuthRepository
) {
    suspend operator fun invoke(policyVersion: Int): Result<Unit> {
        consent.recordConsent(policyVersion).onFailure { return Result.failure(it) }
        return auth.refreshClaims().fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { Result.failure(if (it is AuthFailure.Network) ConsentFailure.Network else ConsentFailure.Unknown(it)) }
        )
    }
}
