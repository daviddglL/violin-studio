package com.violinstudio.domain.feature.auth.usecase

import com.violinstudio.domain.common.RetryBackoff
import com.violinstudio.domain.feature.auth.model.SessionClaims
import com.violinstudio.domain.feature.auth.repository.AuthRepository

internal const val CLAIMS_REFRESH_ATTEMPTS = 3

/** Los claims refrescados no reflejan aún el cambio del servidor (p. ej. `consentOk` sigue falso). */
internal class StaleClaimsException : Exception("Claims desactualizados tras el refresco")

@Suppress("UNUSED_PARAMETER")
internal suspend fun AuthRepository.refreshClaims(
    backoff: RetryBackoff,
    attempts: Int = CLAIMS_REFRESH_ATTEMPTS,
    isFresh: (SessionClaims) -> Boolean = { true }
): Result<SessionClaims> {
    var last: Result<SessionClaims> = Result.failure(IllegalStateException("sin intentos"))
    repeat(attempts) {
        last = claims(forceRefresh = true)
        if (last.isSuccess) return last
    }
    return last
}
