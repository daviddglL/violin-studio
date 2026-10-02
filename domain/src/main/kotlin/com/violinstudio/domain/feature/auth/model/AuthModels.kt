package com.violinstudio.domain.feature.auth.model

import com.violinstudio.domain.feature.profile.model.Role

enum class AuthProvider { PASSWORD, GOOGLE }

data class AuthUser(
    val uid: String,
    val email: String?,
    val emailVerified: Boolean,
    val providers: Set<AuthProvider>
) {
    val usesPassword: Boolean get() = AuthProvider.PASSWORD in providers

    override fun toString(): String = "AuthUser(emailVerified=$emailVerified, providers=$providers)"
}

/** Claims del token: `role` es nulo si el servidor envía un valor que el cliente no conoce. */
data class SessionClaims(val role: Role?, val consentOk: Boolean)

/**
 * Token de Google y el nonce SIN hash con el que se pidió (Firebase lo verifica contra el `nonce` hasheado del token).
 * Ni el token ni el nonce aparecen en `toString`, para que no lleguen a logs.
 */
class GoogleIdToken(val value: String, val rawNonce: String? = null) {
    init {
        require(value.isNotBlank()) { "GoogleIdToken vacío" }
    }

    override fun toString(): String = "GoogleIdToken(***)"
}
