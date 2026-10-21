package com.violinstudio.data.feature.recording.utils

import com.violinstudio.domain.feature.profile.model.Role
import com.violinstudio.domain.feature.recording.model.RecordingAccount

/**
 * Lee de `users/{uid}` lo que decide si se puede grabar y subir. Falla cerrado: un campo ilegible cuenta como menor,
 * sin consentimiento, version y epoca 0; un borrado en curso anula el consentimiento base (como `recordingOk`).
 */
object RecordingAccountParser {
    fun parse(data: Map<*, *>): RecordingAccount = RecordingAccount(
        role = Role.fromWire(data["role"] as? String) ?: Role.INDEPENDENT,
        isMinor = data["isMinor"] as? Boolean ?: true,
        baseConsentGranted = data["consentStatus"] == GRANTED && data["deletion"] == null,
        recordingConsentGranted = data["recordingConsent"] == GRANTED,
        recordingConsentVersion = (data["recordingConsentVersion"] as? Number)?.whole()?.toInt() ?: 0,
        consentEpoch = (data["recordingConsentEpoch"] as? Number)?.whole() ?: 0,
        liveCount = (data["recordingCount"] as? Number)?.whole()?.toInt() ?: 0,
        guardianPending = data["recordingConsent"] == PARENTAL_PENDING
    )

    private const val GRANTED = "granted"
    private const val PARENTAL_PENDING = "parental_pending"

    private fun Number.whole(): Long? {
        val value = toDouble()
        return if (value % 1.0 == 0.0 && value in 0.0..Int.MAX_VALUE.toDouble()) value.toLong() else null
    }
}
