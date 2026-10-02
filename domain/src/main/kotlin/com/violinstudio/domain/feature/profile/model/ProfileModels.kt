package com.violinstudio.domain.feature.profile.model

import com.violinstudio.domain.feature.profile.failure.ProfileFailure
import com.violinstudio.domain.feature.profile.failure.ProfileField
import java.time.LocalDate

enum class Role(val wire: String, val assignableAtRegistration: Boolean) {
    INDEPENDENT("independent", true),
    STUDENT("student", true),

    /** Solo lo asigna el servidor (fase 4); ningún formulario de registro lo ofrece. */
    TEACHER("teacher", false);

    companion object {
        fun fromWire(value: String?): Role? = entries.firstOrNull { it.wire == value }
    }
}

enum class Instrument(val wire: String) {
    VIOLIN("violin"),
    VIOLA("viola"),
    CELLO("cello"),
    DOUBLE_BASS("double_bass"),
    OTHER("other");

    companion object {
        fun fromWire(value: String?): Instrument? = entries.firstOrNull { it.wire == value }
    }
}

enum class ConsentStatus(val wire: String) {
    PENDING("pending"),
    PARENTAL_PENDING("parental_pending"),
    GRANTED("granted"),
    REVOKED("revoked");

    companion object {
        fun fromWire(value: String?): ConsentStatus? = entries.firstOrNull { it.wire == value }

        /** Fail-closed: un estado ausente o desconocido (p. ej. de una versión futura) se trata como `PENDING`. */
        fun fromWireOrPending(value: String?): ConsentStatus = fromWire(value) ?: PENDING
    }
}

/** Resumen del tutor que expone el servidor: nunca el email completo (ni siquiera enmascarado en logs). */
data class GuardianSummary(val emailMasked: String, val sends: Int) {
    override fun toString(): String = "GuardianSummary(sends=$sends)"
}

/**
 * Perfil del usuario. Contrato fail-closed para quien lo construya desde datos remotos: un `consentStatus`
 * desconocido o futuro se trata como `PENDING` ([ConsentStatus.fromWireOrPending]) y un `isMinor` ausente
 * como `true` ([minorOrTrue]).
 */
data class UserProfile(
    val uid: String,
    val displayName: String,
    val instrument: Instrument,
    val locale: String,
    val role: Role,
    val isMinor: Boolean,
    val consentStatus: ConsentStatus,
    val policyVersion: Int?,
    val guardian: GuardianSummary?,
    val deletionInProgress: Boolean
) {
    /**
     * `granted` aceptado exactamente en la versión [requiredVersion]. Igualdad, no `>=`, como el `consentOk` del
     * servidor: una versión mayor que la vigente tampoco concede acceso.
     */
    fun isConsentCurrent(requiredVersion: Int): Boolean =
        consentStatus == ConsentStatus.GRANTED && policyVersion == requiredVersion

    override fun toString(): String = "UserProfile(consentStatus=$consentStatus, isMinor=$isMinor)"

    companion object {
        /** Fail-closed: si el servidor no dice `isMinor`, se asume menor. */
        fun minorOrTrue(raw: Boolean?): Boolean = raw ?: true
    }
}

/**
 * Datos de registro, con las reglas de `registerProfile`: nombre recortado de 1 a 40 puntos de código y solo
 * caracteres de la lista blanca Unicode; locale `^[a-z]{2}(-[A-Z]{2})?$`. Sin `role`: lo asigna el servidor.
 * Solo se crea con [create], que guarda el nombre ya recortado.
 */
@ConsistentCopyVisibility
data class ProfileRegistration private constructor(
    val birthDate: LocalDate,
    val displayName: String,
    val instrument: Instrument,
    val locale: String
) {
    override fun toString(): String = "ProfileRegistration(instrument=$instrument, locale=$locale)"

    companion object {
        fun create(
            birthDate: LocalDate,
            displayName: String,
            instrument: Instrument,
            locale: String
        ): Result<ProfileRegistration> {
            val name = ProfileRules.displayName(displayName, ProfileRules.Unit.CODE_POINTS)
                ?: return invalid(ProfileField.DISPLAY_NAME)
            if (!ProfileRules.isValidLocale(locale)) return invalid(ProfileField.LOCALE)
            return Result.success(ProfileRegistration(birthDate, name, instrument, locale))
        }
    }
}

/**
 * Los tres únicos campos que el dueño edita. Se escriben directamente en Firestore, cuyas reglas son más
 * estrictas que las del servidor: el nombre va recortado y mide como máximo 40 unidades UTF-16 (un emoji
 * fuera del BMP cuenta 2, así que caben 20). Solo se crea con [create].
 */
@ConsistentCopyVisibility
data class EditableProfile private constructor(
    val displayName: String,
    val instrument: Instrument,
    val locale: String
) {
    override fun toString(): String = "EditableProfile(instrument=$instrument, locale=$locale)"

    companion object {
        fun create(displayName: String, instrument: Instrument, locale: String): Result<EditableProfile> {
            val name = ProfileRules.displayName(displayName, ProfileRules.Unit.UTF16_UNITS)
                ?: return invalid(ProfileField.DISPLAY_NAME)
            if (!ProfileRules.isValidLocale(locale)) return invalid(ProfileField.LOCALE)
            return Result.success(EditableProfile(name, instrument, locale))
        }
    }
}

private fun <T> invalid(field: ProfileField): Result<T> = Result.failure(ProfileFailure.InvalidInput(field))
