package com.violinstudio.data.feature.auth.utils.extensions

import com.violinstudio.data.feature.auth.dto.AuthUserDto
import com.violinstudio.data.feature.auth.dto.ClaimsDto
import com.violinstudio.domain.feature.auth.model.AuthProvider
import com.violinstudio.domain.feature.auth.model.AuthUser
import com.violinstudio.domain.feature.auth.model.SessionClaims
import com.violinstudio.domain.feature.profile.model.Role

private const val PASSWORD_PROVIDER = "password"
private const val GOOGLE_PROVIDER = "google.com"

/** Google ya verifica el email: con ese proveedor `emailVerified` es verdadero aunque el SDK no lo refleje. */
fun AuthUserDto.toDomain(): AuthUser {
    val providers = providerIds.mapNotNull {
        when (it) {
            PASSWORD_PROVIDER -> AuthProvider.PASSWORD
            GOOGLE_PROVIDER -> AuthProvider.GOOGLE
            else -> null
        }
    }.toSet()
    return AuthUser(
        uid = uid,
        email = email,
        emailVerified = emailVerified || AuthProvider.GOOGLE in providers,
        providers = providers
    )
}

fun ClaimsDto.toDomain(): SessionClaims = SessionClaims(role = Role.fromWire(role), consentOk = consentOk)
