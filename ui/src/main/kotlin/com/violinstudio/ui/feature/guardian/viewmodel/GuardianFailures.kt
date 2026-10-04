package com.violinstudio.ui.feature.guardian.viewmodel

import com.violinstudio.domain.feature.consent.failure.ConsentFailure
import kotlinx.coroutines.CancellationException

/** Cómo termina una solicitud al tutor, común a la pantalla de petición y a la de espera. */
internal sealed interface GuardianOutcome {
    data object EmailRejected : GuardianOutcome
    data object AlreadyApproved : GuardianOutcome
    data class Failed(val error: GuardianRequestError, val retryAfterSeconds: Long? = null) : GuardianOutcome
}

/** Cada [ConsentFailure] cae en un resultado explícito: terminal (UNAVAILABLE) o reintentable. */
internal fun Throwable.toGuardianOutcome(): GuardianOutcome = when (this) {
    ConsentFailure.GuardianEmailInvalid -> GuardianOutcome.EmailRejected
    is ConsentFailure.InvalidArgument ->
        if (field == "guardianEmail") {
            GuardianOutcome.EmailRejected
        } else {
            GuardianOutcome.Failed(GuardianRequestError.UNKNOWN)
        }
    ConsentFailure.UnderageNotAllowed,
    ConsentFailure.NoProfile,
    ConsentFailure.EmailNotVerified -> GuardianOutcome.Failed(GuardianRequestError.UNAVAILABLE)
    ConsentFailure.AlreadyGranted -> GuardianOutcome.AlreadyApproved
    is ConsentFailure.RateLimited -> GuardianOutcome.Failed(GuardianRequestError.RATE_LIMITED, retryAfterSeconds)
    ConsentFailure.NotMinor -> GuardianOutcome.Failed(GuardianRequestError.NOT_MINOR)
    ConsentFailure.Network -> GuardianOutcome.Failed(GuardianRequestError.NETWORK)
    else -> GuardianOutcome.Failed(GuardianRequestError.UNKNOWN)
}

internal suspend fun <T> runCatchingNonCancellation(block: suspend () -> Result<T>): Result<T> = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
