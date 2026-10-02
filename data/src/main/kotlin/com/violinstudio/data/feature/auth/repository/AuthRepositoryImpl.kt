package com.violinstudio.data.feature.auth.repository

import com.violinstudio.data.commons.utils.resultOf
import com.violinstudio.data.feature.auth.datasource.AuthRemoteDataSource
import com.violinstudio.data.feature.auth.utils.AuthErrorMapper
import com.violinstudio.data.feature.auth.utils.AuthOperation
import com.violinstudio.data.feature.auth.utils.extensions.toDomain
import com.violinstudio.domain.feature.auth.model.AuthUser
import com.violinstudio.domain.feature.auth.model.GoogleIdToken
import com.violinstudio.domain.feature.auth.model.SessionClaims
import com.violinstudio.domain.feature.auth.repository.AuthRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AuthRepositoryImpl @Inject constructor(private val remote: AuthRemoteDataSource) : AuthRepository {
    override val authUser: Flow<AuthUser?> = remote.authUser.map { it?.toDomain() }

    override suspend fun signInWithEmail(email: String, password: String): Result<AuthUser> =
        attempt(AuthOperation.SIGN_IN) { remote.signInWithEmail(email, password).toDomain() }

    override suspend fun signUpWithEmail(email: String, password: String): Result<AuthUser> =
        attempt(AuthOperation.SIGN_UP) { remote.signUpWithEmail(email, password).toDomain() }

    override suspend fun signInWithGoogle(token: GoogleIdToken): Result<AuthUser> =
        attempt(AuthOperation.GOOGLE_SIGN_IN) { remote.signInWithGoogle(token.value).toDomain() }

    override suspend fun sendEmailVerification(): Result<Unit> = attempt { remote.sendEmailVerification() }

    override suspend fun reloadAndRefreshToken(): Result<AuthUser> = attempt { remote.reloadAndRefreshToken().toDomain() }

    override suspend fun sendPasswordReset(email: String): Result<Unit> =
        attempt(AuthOperation.PASSWORD_RESET) { remote.sendPasswordReset(email) }

    override suspend fun claims(forceRefresh: Boolean): Result<SessionClaims> =
        attempt { remote.claims(forceRefresh).toDomain() }

    override suspend fun reauthenticateWithPassword(password: String): Result<Unit> =
        attempt { remote.reauthenticateWithPassword(password) }

    override suspend fun reauthenticateWithGoogle(token: GoogleIdToken): Result<Unit> =
        attempt(AuthOperation.GOOGLE_SIGN_IN) { remote.reauthenticateWithGoogle(token.value) }

    override suspend fun signOut() = remote.signOut()

    private suspend inline fun <T> attempt(operation: AuthOperation = AuthOperation.OTHER, block: () -> T): Result<T> =
        resultOf({ AuthErrorMapper.map(it, operation) }, block)
}
