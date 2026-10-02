package com.violinstudio.data.feature.auth.repository

import com.violinstudio.data.feature.auth.datasource.AuthRemoteDataSource
import com.violinstudio.domain.feature.auth.model.AuthUser
import com.violinstudio.domain.feature.auth.model.GoogleIdToken
import com.violinstudio.domain.feature.auth.model.SessionClaims
import com.violinstudio.domain.feature.auth.repository.AuthRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class AuthRepositoryImpl @Inject constructor(private val remote: AuthRemoteDataSource) : AuthRepository {
    override val authUser: Flow<AuthUser?> = emptyFlow()

    override suspend fun signInWithEmail(email: String, password: String): Result<AuthUser> = TODO()

    override suspend fun signUpWithEmail(email: String, password: String): Result<AuthUser> = TODO()

    override suspend fun signInWithGoogle(token: GoogleIdToken): Result<AuthUser> = TODO()

    override suspend fun sendEmailVerification(): Result<Unit> = TODO()

    override suspend fun reloadAndRefreshToken(): Result<AuthUser> = TODO()

    override suspend fun sendPasswordReset(email: String): Result<Unit> = TODO()

    override suspend fun claims(forceRefresh: Boolean): Result<SessionClaims> = TODO()

    override suspend fun reauthenticateWithPassword(password: String): Result<Unit> = TODO()

    override suspend fun reauthenticateWithGoogle(token: GoogleIdToken): Result<Unit> = TODO()

    override suspend fun signOut(): Unit = TODO()
}
