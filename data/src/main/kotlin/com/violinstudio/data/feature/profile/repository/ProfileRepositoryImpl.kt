package com.violinstudio.data.feature.profile.repository

import com.violinstudio.data.commons.firebase.FunctionsErrorMapper
import com.violinstudio.data.commons.utils.resultOf
import com.violinstudio.data.feature.profile.datasource.IdentityFunctionsDataSource
import com.violinstudio.data.feature.profile.datasource.ProfileRemoteDataSource
import com.violinstudio.data.feature.profile.utils.ProfileErrorMapper
import com.violinstudio.data.feature.profile.utils.UserProfileParser
import com.violinstudio.data.feature.profile.utils.extensions.toDomain
import com.violinstudio.domain.feature.profile.model.EditableProfile
import com.violinstudio.domain.feature.profile.model.ProfileRegistration
import com.violinstudio.domain.feature.profile.model.UserProfile
import com.violinstudio.domain.feature.profile.repository.ProfileRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

class ProfileRepositoryImpl @Inject constructor(
    private val remote: ProfileRemoteDataSource,
    private val functions: IdentityFunctionsDataSource
) : ProfileRepository {
    /** El flujo falla con un `ProfileFailure` (`NoProfile` si no hay perfil legible); la sesión lo reintenta. */
    override fun observe(uid: String): Flow<UserProfile?> = remote.observe(uid)
        .map { data -> data?.let { UserProfileParser.parse(it).toDomain(uid) } }
        .catch { e -> throw if (e is CancellationException) e else ProfileErrorMapper.fromListener(e) }

    override suspend fun register(registration: ProfileRegistration): Result<Unit> =
        resultOf(FunctionsErrorMapper::toProfileFailure) {
            functions.registerProfile(
                mapOf(
                    "birthDate" to registration.birthDate.toString(),
                    "displayName" to registration.displayName,
                    "instrument" to registration.instrument.wire,
                    "locale" to registration.locale
                )
            )
            Unit
        }

    override suspend fun update(uid: String, profile: EditableProfile): Result<Unit> =
        resultOf(ProfileErrorMapper::fromUpdate) {
            remote.update(
                uid,
                mapOf(
                    "displayName" to profile.displayName,
                    "instrument" to profile.instrument.wire,
                    "locale" to profile.locale
                )
            )
        }
}
