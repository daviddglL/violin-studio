package com.violinstudio.domain.feature.recording.model

/**
 * Grabacion ya procesada por el servidor (`users/{uid}/recordings/{id}`). [isDemo]: demostracion del profesor para un
 * alumno. Ni ids ni rutas salen en `toString`. Las `rejected` caducan solas en el servidor; aqui se muestran para que el usuario sepa que no llego.
 */
data class RecordingSummary(
    val id: String,
    val createdAtMs: Long?,
    val durationMs: Long?,
    val ready: Boolean,
    val isDemo: Boolean,
    /** Ruta del objeto en Storage que guarda finalize (`recordings/` o `demos/`); nula en un doc sin ella. */
    val objectPath: String? = null,
    /** Tarea a la que va ligada (las demos siempre llevan una). */
    val assignmentId: String? = null,
    /** Alumno con quien se comparte una demo. */
    val sharedWith: String? = null,
    /** Audio o video difuminado (4c); los docs anteriores al video son audio. */
    val kind: MediaKind = MediaKind.AUDIO
) {
    override fun toString(): String = "RecordingSummary(ready=$ready, isDemo=$isDemo)"
}

/** Lo que la pantalla de grabaciones necesita saber antes de ofrecer el boton de grabar. */
sealed interface RecordingAccess {
    /** Flags del servidor apagados (o menor sin `MINOR_RECORDING_ENABLED`): no se ofrece grabar. */
    data object Disabled : RecordingAccess

    /**
     * Falta el consentimiento de grabacion vigente. [selfGrant] `false`: menor de la edad digital, solo su tutor puede
     * concederlo (por correo). [version]: la que se acepta al conceder. [guardianPending]: ya se pidio al tutor y
     * esta pendiente de su respuesta.
     */
    data class NeedsConsent(
        val selfGrant: Boolean,
        val version: Int,
        val guardianPending: Boolean = false
    ) : RecordingAccess

    /**
     * Se puede grabar. [maxDurationMs] por rol; [quotaFull] con 20 grabaciones vivas o en cola; [videoEnabled] si
     * ademas se ofrece grabar video (flag `VIDEO_ENABLED`).
     */
    data class Allowed(
        val maxDurationMs: Long,
        val quotaFull: Boolean,
        val videoEnabled: Boolean = false
    ) : RecordingAccess
}

/** Cola local + grabaciones del servidor del usuario [uid]; una toma que ya esta en el servidor no se repite. */
data class RecordingsOverview(
    val uid: String,
    val pending: List<QueuedRecording>,
    val recordings: List<RecordingSummary>
) {
    override fun toString(): String = "RecordingsOverview(pending=${pending.size}, recordings=${recordings.size})"
}
