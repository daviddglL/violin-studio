package com.violinstudio.data.feature.account.repository

import com.violinstudio.data.feature.auth.datasource.AuthRemoteDataSource
import com.violinstudio.data.feature.profile.datasource.IdentityFunctionsDataSource
import com.violinstudio.domain.feature.account.repository.AccountRepository
import javax.inject.Inject

class AccountRepositoryImpl @Inject constructor(
    private val functions: IdentityFunctionsDataSource,
    private val auth: AuthRemoteDataSource
) : AccountRepository {
    override suspend fun deleteAccount(): Result<Unit> = Result.success(Unit)
}
