package com.violinstudio.domain.feature.recording.model

import com.violinstudio.domain.feature.profile.model.Role

/**
 * Limites de grabacion del cliente (REQ-REC-01). Una sola definicion por plataforma: `RecordingLimitsTest` la compara
 * con `functions/src/config/recordings.ts`, que a su vez vigila la paridad con `storage.rules`.
 */
object RecordingLimits {
    const val STUDENT_MAX_MS = 60_000L
    const val TEACHER_MAX_MS = 120_000L
    const val MAX_AUDIO_BYTES = 25L * 1024 * 1024

    /** Video ya difuminado (4c): tope de `storage.rules` y de `finalizeRecording`. */
    const val MAX_VIDEO_BYTES = 60L * 1024 * 1024

    /** Version del sello `blurPolicyVersion` que el servidor exige; cambia si cambia [FaceBlurPolicy]. */
    const val BLUR_POLICY_VERSION = 1

    /** Cupo de grabaciones vivas por dueno, contando las que esperan en la cola local (REQ-REC-14). */
    const val MAX_RECORDINGS_PER_OWNER = 20

    /** Margen que el servidor concede sobre el limite por el redondeo del contenedor (`DURATION_TOLERANCE_SEC`). */
    const val SERVER_TOLERANCE_MS = 500L

    /** Duracion minima que el servidor acepta (`MIN_DURATION_SEC`): una toma mas corta se descarta en el cliente. */
    const val MIN_DURATION_MS = 500L

    fun maxDurationMs(role: Role): Long = if (role == Role.TEACHER) TEACHER_MAX_MS else STUDENT_MAX_MS

    /**
     * Instante de la parada automatica: `limite - tolerancia`. El contenedor puede pasarse unas decimas y el servidor
     * rechaza todo lo que supere `limite + tolerancia`; parar aqui deja margen para el redondeo de `mvhd`.
     */
    fun autoStopMs(role: Role): Long = maxDurationMs(role) - SERVER_TOLERANCE_MS
}
