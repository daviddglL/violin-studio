package com.violinstudio.domain.feature.profile.model

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
    }
}

/** Resumen del tutor que expone el servidor: nunca el email completo. */
data class GuardianSummary(val emailMasked: String, val sends: Int)

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
    /** `granted` aceptado en una versión de la política igual o posterior a [requiredVersion]. */
    fun isConsentCurrent(requiredVersion: Int): Boolean =
        consentStatus == ConsentStatus.GRANTED && policyVersion != null && policyVersion >= requiredVersion
}

/** Datos de registro. Sin `role`: lo asigna el servidor. Las mismas reglas que valida `registerProfile`. */
data class ProfileRegistration(
    val birthDate: LocalDate,
    val displayName: String,
    val instrument: Instrument,
    val locale: String
) {
    init {
        ProfileRules.requireDisplayName(displayName)
        ProfileRules.requireLocale(locale)
    }
}

/** Los tres únicos campos que el dueño puede editar. */
data class EditableProfile(val displayName: String, val instrument: Instrument, val locale: String) {
    init {
        ProfileRules.requireDisplayName(displayName)
        ProfileRules.requireLocale(locale)
    }
}

internal object ProfileRules {
    const val DISPLAY_NAME_MAX = 40
    private val LOCALE = Regex("^[a-z]{2}(-[A-Z]{2})?$")

    fun requireDisplayName(value: String) {
        val length = value.trim().let { it.codePointCount(0, it.length) }
        require(length in 1..DISPLAY_NAME_MAX) { "displayName debe tener entre 1 y $DISPLAY_NAME_MAX caracteres" }
    }

    fun requireLocale(value: String) = require(LOCALE.matches(value)) { "locale inválido" }
}
