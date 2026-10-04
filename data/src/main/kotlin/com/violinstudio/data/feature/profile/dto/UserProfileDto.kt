package com.violinstudio.data.feature.profile.dto

/**
 * Documento `users/{uid}` ya tolerante: cada campo es nulo si faltaba o tenía otro tipo. NO incluye
 * `birthDate`: el cliente nunca necesita volver a leer la fecha de nacimiento.
 */
data class UserProfileDto(
    val displayName: String?,
    val instrument: String?,
    val locale: String?,
    val role: String?,
    val isMinor: Boolean?,
    val consentStatus: String?,
    val policyVersion: Int?,
    val guardianEmailMasked: String?,
    val guardianSends: Int,
    val deletionInProgress: Boolean
) {
    override fun toString(): String = "UserProfileDto(consentStatus=$consentStatus, isMinor=$isMinor)"
}
