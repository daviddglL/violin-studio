package com.violinstudio.domain.feature.consent.model

/** Respuesta del callable `identityConfig`: la política legal la decide el servidor. */
data class IdentityConfig(
    val policyVersion: Int,
    val policyUrl: String,
    val digitalConsentAge: Int,
    val guardianFlowEnabled: Boolean
)

/** Acuse de `requestGuardianConsent`: solo el email enmascarado. */
data class GuardianRequestReceipt(val emailMasked: String)
