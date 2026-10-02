package com.violinstudio.data.feature.profile.repository

import com.violinstudio.data.feature.profile.datasource.IdentityFunctionsDataSource
import com.violinstudio.data.feature.profile.datasource.ProfileRemoteDataSource
import com.violinstudio.domain.feature.profile.model.EditableProfile
import com.violinstudio.domain.feature.profile.model.ProfileRegistration
import com.violinstudio.domain.feature.profile.model.UserProfile
import com.violinstudio.domain.feature.profile.repository.ProfileRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class ProfileRepositoryImpl @Inject constructor(
    private val remote: ProfileRemoteDataSource,
    private val functions: IdentityFunctionsDataSource
) : ProfileRepository {
    override fun observe(uid: String): Flow<UserProfile?> = emptyFlow()

    override suspend fun register(registration: ProfileRegistration): Result<Unit> = TODO()

    override suspend fun update(uid: String, profile: EditableProfile): Result<Unit> = TODO()
}
