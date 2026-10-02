package com.violinstudio.domain.feature.auth.usecase

import com.violinstudio.domain.feature.auth.model.SessionClaims
import com.violinstudio.domain.feature.auth.repository.AuthRepository

internal const val CLAIMS_REFRESH_ATTEMPTS = 3

/** Fuerza el refresco del token; reintenta si falla (sin esperas) hasta [attempts] veces y devuelve el último fallo. */
internal suspend fun AuthRepository.refreshClaims(attempts: Int = CLAIMS_REFRESH_ATTEMPTS): Result<SessionClaims> {
    var last: Result<SessionClaims> = Result.failure(IllegalStateException("sin intentos"))
    repeat(attempts) {
        last = claims(forceRefresh = true)
        if (last.isSuccess) return last
    }
    return last
}
