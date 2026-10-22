package com.violinstudio.domain.feature.recording.model

import com.violinstudio.domain.feature.profile.model.Role
import com.violinstudio.domain.feature.recording.failure.RecordingFailure

/**
 * Lo que el cliente necesita de `users/{uid}` para decidir si puede grabar (el servidor repite todas las comprobaciones).
 * [consentEpoch] es la `recordingConsentEpoch` vigente (ausente = 0); [liveCount] es `recordingCount` (ausente = 0).
 */
data class RecordingAccount(
    val role: Role,
    val isMinor: Boolean,
    /** `consentStatus == granted` en el doc base (ademas del consentimiento de grabacion). */
    val baseConsentGranted: Boolean,
    val recordingConsentGranted: Boolean,
    val recordingConsentVersion: Int,
    val consentEpoch: Long,
    val liveCount: Int,
    /** `recordingConsent == parental_pending`: se pidió permiso al tutor y aún no ha respondido. */
    val guardianPending: Boolean = false
) {
    override fun toString(): String = "RecordingAccount(role=$role, isMinor=$isMinor)"
}

/**
 * Parametros de una toma. [consentEpoch] se captura al EMPEZAR a grabar y viaja con la toma hasta la subida, que la
 * envia siempre como metadato `recordingConsentEpoch` (W3 de B4b). [autoStopMs] <= [maxDurationMs] - tolerancia.
 */
data class RecordingSpec(
    val id: String,
    val uid: String,
    val maxDurationMs: Long,
    val autoStopMs: Long,
    val consentEpoch: Long
) {
    override fun toString(): String = "RecordingSpec(maxDurationMs=$maxDurationMs)"
}

/** Toma terminada: fichero `.m4a` completo (el `.part` ya se renombro). Ni id, uid ni ruta salen en `toString`. */
data class RecordedTake(
    val id: String,
    val uid: String,
    val path: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val consentEpoch: Long
) {
    override fun toString(): String = "RecordedTake(durationMs=$durationMs, sizeBytes=$sizeBytes)"
}

/** Final de una toma que no provoco el usuario: parada automatica al limite o fallo del recorder. */
sealed interface RecordingOutcome {
    data class AutoStopped(val take: RecordedTake) : RecordingOutcome

    data class Failed(val failure: RecordingFailure) : RecordingOutcome
}

/** Video difuminado listo para subir con el sello `blurApplied`/`blurPolicyVersion`. Sin ruta en `toString`. */
data class BlurredVideo(
    val path: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val policyVersion: Int
) {
    override fun toString(): String = "BlurredVideo(durationMs=$durationMs, sizeBytes=$sizeBytes, v=$policyVersion)"
}
