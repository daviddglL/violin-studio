package com.violinstudio.domain.feature.consent.repository

import com.violinstudio.domain.feature.consent.model.GuardianRequestReceipt
import com.violinstudio.domain.feature.consent.model.IdentityConfig

/** Los fallos llegan como `Result.failure(ConsentFailure)`. */
interface ConsentRepository {
    suspend fun identityConfig(): Result<IdentityConfig>

    suspend fun recordConsent(policyVersion: Int): Result<Unit>

    suspend fun revokeConsent(): Result<Unit>

    suspend fun requestGuardianConsent(guardianEmail: String): Result<GuardianRequestReceipt>
}
