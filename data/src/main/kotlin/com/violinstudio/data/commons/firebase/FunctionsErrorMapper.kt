package com.violinstudio.data.commons.firebase

import com.violinstudio.domain.feature.account.failure.AccountFailure
import com.violinstudio.domain.feature.consent.failure.ConsentFailure
import com.violinstudio.domain.feature.link.failure.LinkFailure
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
    private const val MAX_CAUSE_DEPTH = 8

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
        val details = Details.of(error)
        // Antes que la rama sin red: comparte código `unavailable` con la caída de red, pero es del servidor.
        if (details?.reason == "TEMPORARILY_UNAVAILABLE") return ConsentFailure.TemporarilyUnavailable
        if (isOffline(error)) return ConsentFailure.Network
        if (details == null) return ConsentFailure.Unknown(error)
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
            "RECORDING_DISABLED" -> ConsentFailure.RecordingDisabled
            "RECORDING_CONSENT_OUTDATED" -> ConsentFailure.RecordingConsentOutdated(details.currentVersion)
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

    fun toLinkFailure(error: Throwable): LinkFailure {
        val details = Details.of(error)
        return when (details?.reason) {
            "INVALID_ARGUMENT", "CODE_NOT_FOUND" -> LinkFailure.InvalidCode
            "RATE_LIMITED" -> LinkFailure.RateLimited(details.retryAfterSeconds)
            "TEACHER_FULL" -> LinkFailure.TeacherFull
            "CODE_LIMIT_REACHED" -> LinkFailure.CodeLimitReached
            "ALREADY_LINKED" -> LinkFailure.AlreadyLinked
            "NOTICE_OUTDATED" -> LinkFailure.NoticeOutdated
            "NO_ACTIVE_CONSENT", "NO_PROFILE" -> LinkFailure.NoActiveConsent
            "NOT_ELIGIBLE" -> LinkFailure.NotEligible
            "NOT_TEACHER" -> LinkFailure.NotTeacher
            "NOT_LINK_PARTY" -> LinkFailure.PermissionDenied
            // Antes que la rama sin red: comparte código `unavailable` con la caída de red, pero es del servidor.
            "TEMPORARILY_UNAVAILABLE" -> LinkFailure.TemporarilyUnavailable
            else -> if (isOffline(error)) LinkFailure.Offline else LinkFailure.Unknown
        }
    }

    // El SDK de Functions envuelve el fallo de red en INTERNAL/DEADLINE_EXCEEDED con la IOException como causa.
    private fun isOffline(error: Throwable): Boolean =
        (error is FunctionsCallException && error.code in OFFLINE_CODES) ||
            generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).any { it is IOException }

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
