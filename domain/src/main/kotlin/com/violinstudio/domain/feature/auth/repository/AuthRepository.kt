package com.violinstudio.domain.feature.auth.repository

import com.violinstudio.domain.feature.auth.model.AuthUser
import com.violinstudio.domain.feature.auth.model.GoogleIdToken
import com.violinstudio.domain.feature.auth.model.SessionClaims
import kotlinx.coroutines.flow.Flow

/** Las operaciones suspendidas nunca lanzan salvo cancelación: los fallos son `Result.failure(AuthFailure)`. */
interface AuthRepository {
    /** `null` si no hay sesión. Vuelve a emitir tras [reloadAndRefreshToken]. */
    val authUser: Flow<AuthUser?>

    suspend fun signInWithEmail(email: String, password: String): Result<AuthUser>

    suspend fun signUpWithEmail(email: String, password: String): Result<AuthUser>

    suspend fun signInWithGoogle(token: GoogleIdToken): Result<AuthUser>

    suspend fun sendEmailVerification(): Result<Unit>

    /** Recarga el usuario y fuerza un token nuevo; devuelve el usuario actualizado. */
    suspend fun reloadAndRefreshToken(): Result<AuthUser>

    suspend fun sendPasswordReset(email: String): Result<Unit>

    suspend fun claims(forceRefresh: Boolean): Result<SessionClaims>

    suspend fun reauthenticateWithPassword(password: String): Result<Unit>

    suspend fun reauthenticateWithGoogle(token: GoogleIdToken): Result<Unit>

    suspend fun signOut()
}
