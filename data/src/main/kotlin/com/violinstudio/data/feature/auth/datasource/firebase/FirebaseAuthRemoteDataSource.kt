package com.violinstudio.data.feature.auth.datasource.firebase

import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.OAuthProvider
import com.violinstudio.data.feature.auth.datasource.AuthRemoteDataSource
import com.violinstudio.data.feature.auth.dto.AuthUserDto
import com.violinstudio.data.feature.auth.dto.ClaimsDto
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.tasks.await

/** Adaptador fino sobre el SDK, sin lógica ni traducción de errores; se prueba en el E2E (8b). */
class FirebaseAuthRemoteDataSource @Inject constructor(private val auth: FirebaseAuth) : AuthRemoteDataSource {
    // Uid del usuario cuyo borrado termino pero cuyo cierre de sesion fallo: solo ese usuario se oculta, asi que
    // nunca puede tapar un inicio de sesion posterior (el estado del SDK es global del proceso).
    private val clearedUid = MutableStateFlow<String?>(null)

    private val sdkUser: Flow<AuthUserDto?> = callbackFlow {
        val listener = FirebaseAuth.IdTokenListener { trySend(it.currentUser?.toDto()) }
        auth.addIdTokenListener(listener)
        awaitClose { auth.removeIdTokenListener(listener) }
    }

    override val authUser: Flow<AuthUserDto?> =
        combine(sdkUser, clearedUid) { user, cleared -> maskCleared(user, cleared) }

    override suspend fun signInWithEmail(email: String, password: String): AuthUserDto =
        auth.signInWithEmailAndPassword(email, password).await().user.toDtoOrThrow()
            .also { clearedUid.value = null }

    override suspend fun signUpWithEmail(email: String, password: String): AuthUserDto =
        auth.createUserWithEmailAndPassword(email, password).await().user.toDtoOrThrow()
            .also { clearedUid.value = null }

    override suspend fun signInWithGoogle(idToken: String, rawNonce: String?): AuthUserDto =
        auth.signInWithCredential(googleCredential(idToken, rawNonce)).await().user.toDtoOrThrow()
            .also { clearedUid.value = null }

    override suspend fun sendEmailVerification() {
        currentUser().sendEmailVerification().await()
    }

    override suspend fun reloadAndRefreshToken(): AuthUserDto {
        val user = currentUser()
        user.reload().await()
        user.getIdToken(true).await()
        return user.toDto()
    }

    override suspend fun sendPasswordReset(email: String) {
        auth.sendPasswordResetEmail(email).await()
    }

    override suspend fun claims(forceRefresh: Boolean): ClaimsDto {
        val claims = currentUser().getIdToken(forceRefresh).await().claims
        return ClaimsDto(role = claims["role"] as? String, consentOk = claims["consentOk"] == true)
    }

    override suspend fun reauthenticateWithPassword(password: String) {
        val user = currentUser()
        user.reauthenticate(EmailAuthProvider.getCredential(checkNotNull(user.email), password)).await()
    }

    override suspend fun reauthenticateWithGoogle(idToken: String, rawNonce: String?) {
        currentUser().reauthenticate(googleCredential(idToken, rawNonce)).await()
    }

    // `GoogleAuthProvider.getCredential(idToken, x)` toma un ACCESS TOKEN como segundo argumento, no un nonce: con nonce
    // crudo hay que usar el constructor OAuth, que Firebase verifica contra el `nonce` hasheado del ID token.
    private fun googleCredential(idToken: String, rawNonce: String?): AuthCredential = if (rawNonce == null) {
        GoogleAuthProvider.getCredential(idToken, null)
    } else {
        OAuthProvider.newCredentialBuilder("google.com").setIdTokenWithRawNonce(idToken, rawNonce).build()
    }

    override suspend fun reloadCurrentUser() {
        currentUser().reload().await()
    }

    override fun currentUid(): String? = auth.currentUser?.uid

    override fun signOut() = auth.signOut()

    override fun clearLocalSession() {
        clearedUid.value = auth.currentUser?.uid
    }

    private fun currentUser(): FirebaseUser = checkNotNull(auth.currentUser) { "Sin sesión" }

    private fun FirebaseUser?.toDtoOrThrow(): AuthUserDto = checkNotNull(this) { "El SDK no devolvió usuario" }.toDto()

    private fun FirebaseUser.toDto() = AuthUserDto(
        uid = uid,
        email = email,
        emailVerified = isEmailVerified,
        providerIds = providerData.map { it.providerId }
    )
}

/** Oculta al usuario del SDK solo si es exactamente el que se limpio tras un borrado. */
internal fun maskCleared(user: AuthUserDto?, clearedUid: String?): AuthUserDto? =
    if (user != null && user.uid == clearedUid) null else user
