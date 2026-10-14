package com.violinstudio.domain.feature.consent.model

/** Respuesta del callable `identityConfig`: la política legal la decide el servidor. */
data class IdentityConfig(
    val policyVersion: Int,
    val policyUrl: String,
    val digitalConsentAge: Int,
    val guardianFlowEnabled: Boolean,
    /** Interruptores de 4b/4c: apagados por defecto (servidor antiguo o ausente = valor seguro). */
    val recordingsEnabled: Boolean = false,
    val videoEnabled: Boolean = false,
    val minorRecordingEnabled: Boolean = false,
    /** Versión vigente del consentimiento de grabación, independiente de `policyVersion`. */
    val recordingConsentVersion: Int = 1
)

/** Acuse de `requestGuardianConsent`: solo el email enmascarado. */
data class GuardianRequestReceipt(val emailMasked: String)
