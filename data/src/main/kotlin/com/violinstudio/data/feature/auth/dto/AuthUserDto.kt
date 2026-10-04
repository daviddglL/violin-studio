package com.violinstudio.data.feature.auth.dto

/** Usuario de Firebase Auth sin tipos del SDK; `providerIds` son los `providerId` de `providerData`. */
data class AuthUserDto(
    val uid: String,
    val email: String?,
    val emailVerified: Boolean,
    val providerIds: List<String>
) {
    override fun toString(): String = "AuthUserDto(emailVerified=$emailVerified, providerIds=$providerIds)"
}

/** Claims del ID token que usa la app; `role` llega crudo. */
data class ClaimsDto(val role: String?, val consentOk: Boolean)
