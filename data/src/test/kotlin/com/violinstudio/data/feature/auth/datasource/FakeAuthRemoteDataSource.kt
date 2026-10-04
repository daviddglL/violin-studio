package com.violinstudio.data.feature.auth.datasource

import com.violinstudio.data.feature.auth.dto.AuthUserDto
import com.violinstudio.data.feature.auth.dto.ClaimsDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** Fake escrito a mano: `failure` hace que la siguiente llamada lance; `calls` registra las operaciones. */
class FakeAuthRemoteDataSource : AuthRemoteDataSource {
    private val state = MutableSharedFlow<AuthUserDto?>(replay = 1)
    override val authUser: Flow<AuthUserDto?> = state

    var failure: Exception? = null
    var signOutFailure: Exception? = null
    var user = AuthUserDto("u1", "a@b.co", false, listOf("password"))
    var claims = ClaimsDto("independent", true)
    val calls = mutableListOf<String>()

    fun emit(value: AuthUserDto?) {
        state.tryEmit(value)
    }

    private fun record(call: String) {
        calls += call
        failure?.let { throw it }
    }

    override suspend fun signInWithEmail(email: String, password: String): AuthUserDto {
        record("signIn:$email")
        return user
    }

    override suspend fun signUpWithEmail(email: String, password: String): AuthUserDto {
        record("signUp:$email")
        return user
    }

    override suspend fun signInWithGoogle(idToken: String, rawNonce: String?): AuthUserDto {
        record("google:$idToken:$rawNonce")
        return user
    }

    override suspend fun sendEmailVerification() = record("sendVerification")

    override suspend fun reloadAndRefreshToken(): AuthUserDto {
        record("reload")
        return user.also { emit(it) }
    }

    override suspend fun sendPasswordReset(email: String) = record("reset:$email")

    override suspend fun claims(forceRefresh: Boolean): ClaimsDto {
        record("claims:$forceRefresh")
        return claims
    }

    override suspend fun reauthenticateWithPassword(password: String) = record("reauthPassword")

    override suspend fun reauthenticateWithGoogle(idToken: String, rawNonce: String?) =
        record("reauthGoogle:$idToken:$rawNonce")

    var reloadFailure: Exception? = null

    override suspend fun reloadCurrentUser() {
        calls += "reloadCurrentUser"
        reloadFailure?.let { throw it }
    }

    override fun signOut() {
        calls += "signOut"
        signOutFailure?.let { throw it }
        emit(null)
    }
}
