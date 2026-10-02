package com.violinstudio.data.commons.firebase

import com.violinstudio.domain.feature.account.failure.AccountFailure
import com.violinstudio.domain.feature.consent.failure.ConsentFailure
import com.violinstudio.domain.feature.profile.failure.ProfileFailure

/**
 * Error de un callable sin tipos del SDK. [code] es el nombre del código de Functions (`UNAVAILABLE`,
 * `FAILED_PRECONDITION`...); [details] es `HttpsError.details` (`reason`, `field`, `currentVersion`,
 * `retryAfterSeconds`).
 */
class FunctionsCallException(val code: String, val details: Any?, cause: Throwable? = null) :
    Exception("Functions error: $code", cause)

object FunctionsErrorMapper {
    fun toProfileFailure(error: Throwable): ProfileFailure = TODO()

    fun toConsentFailure(error: Throwable): ConsentFailure = TODO()

    fun toAccountFailure(error: Throwable): AccountFailure = TODO()
}
