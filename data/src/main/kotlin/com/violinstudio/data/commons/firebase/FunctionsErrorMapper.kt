package com.violinstudio.data.commons.firebase

import com.violinstudio.domain.feature.account.failure.AccountFailure
import com.violinstudio.domain.feature.consent.failure.ConsentFailure
import com.violinstudio.domain.feature.profile.failure.ProfileFailure
import com.violinstudio.domain.feature.profile.failure.ProfileField
import java.io.IOException

/**
 * Error de un callable sin tipos del SDK. [code] es el nombre del código de Functions (`UNAVAILABLE`,
 * `FAILED_PRECONDITION`...); [details] es `HttpsError.details` (`reason`, `field`, `currentVersion`,
 * `retryAfterSeconds`).
 */
class FunctionsCallException(val code: String, val details: Any?, cause: Throwable? = null) :
    Exception("Functions error: $code", cause)

/** Traduce `details.reason` (el `ErrorReason` del backend) a los fallos de dominio; nunca expone el mensaje. */
object FunctionsErrorMapper {
    private val OFFLINE_CODES = setOf("UNAVAILABLE", "DEADLINE_EXCEEDED")

    fun toProfileFailure(error: Throwable): ProfileFailure {
        if (isOffline(error)) return ProfileFailure.Network
        val details = Details.of(error) ?: return ProfileFailure.Unknown(error)
        return when (details.reason) {
            "EMAIL_NOT_VERIFIED" -> ProfileFailure.EmailNotVerified
            "INVALID_BIRTH_DATE" -> ProfileFailure.InvalidBirthDate
            "UNDERAGE_NOT_ALLOWED" -> ProfileFailure.UnderageNotAllowed
            "NO_PROFILE" -> ProfileFailure.NoProfile
            "INVALID_ARGUMENT" -> ProfileFailure.InvalidInput(ProfileField.fromWire(details.field))
            else -> ProfileFailure.Unknown(error)
        }
    }

    fun toConsentFailure(error: Throwable): ConsentFailure {
        if (isOffline(error)) return ConsentFailure.Network
        val details = Details.of(error) ?: return ConsentFailure.Unknown(error)
        return when (details.reason) {
            "EMAIL_NOT_VERIFIED" -> ConsentFailure.EmailNotVerified
            "NO_PROFILE" -> ConsentFailure.NoProfile
            "GUARDIAN_REQUIRED" -> ConsentFailure.GuardianRequired
            "POLICY_OUTDATED" -> ConsentFailure.PolicyOutdated(details.currentVersion)
            "NO_ACTIVE_CONSENT" -> ConsentFailure.NoActiveConsent
            "CONSENT_ALREADY_GRANTED" -> ConsentFailure.AlreadyGranted
            "NOT_MINOR" -> ConsentFailure.NotMinor
            "GUARDIAN_EMAIL_INVALID" -> ConsentFailure.GuardianEmailInvalid
            "RATE_LIMITED" -> ConsentFailure.RateLimited(details.retryAfterSeconds)
            "UNDERAGE_NOT_ALLOWED" -> ConsentFailure.UnderageNotAllowed
            "INVALID_ARGUMENT" -> ConsentFailure.InvalidArgument(details.field)
            else -> ConsentFailure.Unknown(error)
        }
    }

    fun toAccountFailure(error: Throwable): AccountFailure {
        if (isOffline(error)) return AccountFailure.Network
        return when (Details.of(error)?.reason) {
            "REAUTH_REQUIRED" -> AccountFailure.RequiresRecentLogin
            "ERASURE_FAILED" -> AccountFailure.ErasureFailed
            else -> AccountFailure.Unknown(error)
        }
    }

    private fun isOffline(error: Throwable) =
        error is IOException || (error is FunctionsCallException && error.code in OFFLINE_CODES)

    private class Details(private val map: Map<*, *>) {
        val reason: String? get() = map["reason"] as? String
        val field: String? get() = map["field"] as? String
        val currentVersion: Int? get() = (map["currentVersion"] as? Number)?.wholeOrNull()?.toInt()
        val retryAfterSeconds: Long? get() = (map["retryAfterSeconds"] as? Number)?.wholeOrNull()

        companion object {
            fun of(error: Throwable): Details? =
                ((error as? FunctionsCallException)?.details as? Map<*, *>)?.let(::Details)
        }
    }

    private fun Number.wholeOrNull(): Long? = toDouble().takeIf { it % 1.0 == 0.0 }?.toLong()
}
