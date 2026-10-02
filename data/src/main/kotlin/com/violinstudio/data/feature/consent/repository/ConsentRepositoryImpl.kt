package com.violinstudio.data.feature.consent.repository

import com.violinstudio.data.feature.profile.datasource.IdentityFunctionsDataSource
import com.violinstudio.domain.feature.consent.model.GuardianRequestReceipt
import com.violinstudio.domain.feature.consent.model.IdentityConfig
import com.violinstudio.domain.feature.consent.repository.ConsentRepository
import javax.inject.Inject

class ConsentRepositoryImpl @Inject constructor(private val functions: IdentityFunctionsDataSource) :
    ConsentRepository {
    override suspend fun identityConfig(): Result<IdentityConfig> = Result.success(IdentityConfig(0, "", 0, false))

    override suspend fun recordConsent(policyVersion: Int): Result<Unit> = Result.success(Unit)

    override suspend fun revokeConsent(): Result<Unit> = Result.success(Unit)

    override suspend fun requestGuardianConsent(guardianEmail: String): Result<GuardianRequestReceipt> =
        Result.success(GuardianRequestReceipt(""))
}
