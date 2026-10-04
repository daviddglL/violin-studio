package com.violinstudio.data.feature.auth.datasource

import com.violinstudio.data.feature.auth.dto.AuthUserDto
import com.violinstudio.data.feature.auth.dto.ClaimsDto
import kotlinx.coroutines.flow.Flow

/**
 * Frontera con Firebase Auth. Las implementaciones no tienen lógica: lanzan las excepciones crudas del SDK y
 * es el repositorio quien las traduce.
 */
interface AuthRemoteDataSource {
    /** Emite en cada cambio de ID token: inicio/cierre de sesión y tras [reloadAndRefreshToken]. `null` sin sesión. */
    val authUser: Flow<AuthUserDto?>

    suspend fun signInWithEmail(email: String, password: String): AuthUserDto

    suspend fun signUpWithEmail(email: String, password: String): AuthUserDto

    suspend fun signInWithGoogle(idToken: String, rawNonce: String?): AuthUserDto

    suspend fun sendEmailVerification()

    /** `reload()` + `getIdToken(true)`: el listener de [authUser] vuelve a emitir con el usuario recargado. */
    suspend fun reloadAndRefreshToken(): AuthUserDto

    suspend fun sendPasswordReset(email: String)

    suspend fun claims(forceRefresh: Boolean): ClaimsDto

    suspend fun reauthenticateWithPassword(password: String)

    suspend fun reauthenticateWithGoogle(idToken: String, rawNonce: String?)

    /** `currentUser.reload()`; lanza `IllegalStateException` si no hay sesión y el error crudo del SDK si falla. */
    suspend fun reloadCurrentUser()

    fun signOut()

    /**
     * Ultimo recurso tras un borrado de cuenta cuyo [signOut] fallo: [authUser] pasa a emitir `null` (sin sesion)
     * hasta el siguiente inicio de sesion, aunque el SDK aun crea que hay usuario.
     */
    fun clearLocalSession()
}
