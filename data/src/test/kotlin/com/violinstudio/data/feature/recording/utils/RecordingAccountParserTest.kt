package com.violinstudio.data.feature.recording.utils

import com.violinstudio.domain.feature.profile.model.Role
import com.violinstudio.domain.feature.recording.model.RecordingAccount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RecordingAccountParserTest {
    @Test
    fun `un perfil completo se lee entero`() {
        val data = mapOf(
            "role" to "teacher",
            "isMinor" to false,
            "consentStatus" to "granted",
            "recordingConsent" to "granted",
            "recordingConsentVersion" to 1L,
            "recordingConsentEpoch" to 4L,
            "recordingCount" to 7L
        )
        assertEquals(RecordingAccount(Role.TEACHER, false, true, true, 1, 4, 7), RecordingAccountParser.parse(data))
    }

    @Test
    fun `lo ausente o ilegible falla cerrado`() {
        val account = RecordingAccountParser.parse(
            mapOf("role" to 3, "isMinor" to "no", "recordingConsentVersion" to 1.5, "recordingConsentEpoch" to -1L)
        )
        assertEquals(RecordingAccount(Role.INDEPENDENT, true, false, false, 0, 0, 0), account)
    }

    @Test
    fun `un borrado en curso anula el consentimiento base y revocar el de grabacion se ve`() {
        val account = RecordingAccountParser.parse(
            mapOf(
                "consentStatus" to "granted",
                "deletion" to mapOf("state" to "in_progress"),
                "recordingConsent" to "revoked"
            )
        )
        assertEquals(false, account.baseConsentGranted)
        assertEquals(false, account.recordingConsentGranted)
    }
}
