package com.violinstudio.data.feature.consent.dto

import com.violinstudio.domain.feature.consent.model.IdentityConfig
import kotlinx.serialization.Serializable

/** `u.{uid}.session.identity_config` (JSON). Los interruptores ausentes quedan apagados, como en [IdentityConfig]. */
@Serializable
data class IdentityConfigDto(
    val policyVersion: Int,
    val policyUrl: String,
    val digitalConsentAge: Int,
    val guardianFlowEnabled: Boolean,
    val recordingsEnabled: Boolean = false,
    val videoEnabled: Boolean = false,
    val minorRecordingEnabled: Boolean = false,
    val recordingConsentVersion: Int = 1,
    val chatEnabled: Boolean = false,
    val notificationsEnabled: Boolean = false
) {
    fun toDomain() = IdentityConfig(
        policyVersion = policyVersion,
        policyUrl = policyUrl,
        digitalConsentAge = digitalConsentAge,
        guardianFlowEnabled = guardianFlowEnabled,
        recordingsEnabled = recordingsEnabled,
        videoEnabled = videoEnabled,
        minorRecordingEnabled = minorRecordingEnabled,
        recordingConsentVersion = recordingConsentVersion,
        chatEnabled = chatEnabled,
        notificationsEnabled = notificationsEnabled
    )

    companion object {
        fun from(config: IdentityConfig) = IdentityConfigDto(
            policyVersion = config.policyVersion,
            policyUrl = config.policyUrl,
            digitalConsentAge = config.digitalConsentAge,
            guardianFlowEnabled = config.guardianFlowEnabled,
            recordingsEnabled = config.recordingsEnabled,
            videoEnabled = config.videoEnabled,
            minorRecordingEnabled = config.minorRecordingEnabled,
            recordingConsentVersion = config.recordingConsentVersion,
            chatEnabled = config.chatEnabled,
            notificationsEnabled = config.notificationsEnabled
        )
    }
}
