package com.violinstudio.domain.feature.auth.usecase

import com.violinstudio.domain.common.RetryBackoff
import com.violinstudio.domain.feature.auth.failure.AuthFailure
import com.violinstudio.domain.feature.auth.model.SessionClaims
import com.violinstudio.domain.feature.auth.repository.AuthRepository
import kotlinx.coroutines.delay

internal const val CLAIMS_REFRESH_ATTEMPTS = 3

/** Los claims refrescados no reflejan aún el cambio del servidor (p. ej. `consentOk` sigue falso). */
internal class StaleClaimsException : Exception("Claims desactualizados tras el refresco")

/**
 * Fuerza el refresco del token hasta [attempts] veces con espera exponencial ([backoff], con `delay`: cancelable) entre
 * intentos, nunca tras el último. Un refresco correcto pero con claims que [isFresh] rechaza cuenta como intento
 * fallido ([StaleClaimsException]). Devuelve el último fallo si se agotan.
 */
internal suspend fun AuthRepository.refreshClaims(
    backoff: RetryBackoff,
    attempts: Int = CLAIMS_REFRESH_ATTEMPTS,
    isFresh: (SessionClaims) -> Boolean = { true }
): Result<SessionClaims> {
    var last: Result<SessionClaims> = Result.failure(IllegalStateException("sin intentos"))
    repeat(attempts) { attempt ->
        val result = claims(forceRefresh = true)
        last = result.fold(
            onSuccess = { if (isFresh(it)) result else Result.failure(StaleClaimsException()) },
            onFailure = { result }
        )
        if (last.isSuccess) return last
        if (attempt < attempts - 1) delay(backoff.delayFor(attempt))
    }
    return last
}

/** `Network` y `TooManyRequests` son transitorios: la UI los trata como "sin conexión, reintenta". */
internal fun Throwable.isNetworkLike(): Boolean = this is AuthFailure.Network || this is AuthFailure.TooManyRequests
