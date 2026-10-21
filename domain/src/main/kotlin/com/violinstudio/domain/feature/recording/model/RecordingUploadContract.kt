package com.violinstudio.domain.feature.recording.model

/**
 * Lo que `storage.rules` y `finalizeRecording` esperan de una subida de grabacion propia (audio): ruta, tipo y
 * metadatos personalizados. `RecordingUploadContractTest` lo compara con las reglas. La subida (B7b) usa SIEMPRE
 * [customMetadata]: sin el sello [EPOCH_KEY] o con una epoca distinta de la vigente, las reglas deniegan y finalize
 * rechaza con `consent` (el clasificador de B7a trata ese 403 como consentimiento y borra la toma).
 */
object RecordingUploadContract {
    const val CONTENT_TYPE = "audio/mp4"
    const val RECORDINGS_DIR = "recordings"

    /** Demostraciones del profesor para un alumno: ruta disjunta de las grabaciones (C1), mismo doc de Firestore. */
    const val DEMOS_DIR = "demos"

    /** Metadato obligatorio: la `recordingConsentEpoch` capturada al EMPEZAR a grabar (decimal canonico). */
    const val EPOCH_KEY = "recordingConsentEpoch"
    const val KIND_KEY = "kind"
    const val DURATION_KEY = "durationMs"
    const val KIND_AUDIO = "audio"
    const val ASSIGNMENT_KEY = "assignmentId"
    const val SHARED_WITH_KEY = "sharedWith"

    private const val MAX_EPOCH = 999_999_999L
    private const val MAX_DURATION_MS = 999_999L
    private val ASSIGNMENT_ID = Regex("^[A-Za-z0-9-]{1,64}$")
    private val SHARED_WITH = Regex("^[A-Za-z0-9]{1,128}$")

    fun objectPath(take: RecordedTake): String = "users/${take.uid}/$RECORDINGS_DIR/${take.id}.m4a"

    fun customMetadata(take: RecordedTake): Map<String, String> {
        require(take.consentEpoch in 0..MAX_EPOCH) { "epoca fuera del rango de las reglas" }
        require(take.durationMs in 0..MAX_DURATION_MS) { "duracion fuera del rango de las reglas" }
        return mapOf(
            KIND_KEY to KIND_AUDIO,
            DURATION_KEY to take.durationMs.toString(),
            EPOCH_KEY to take.consentEpoch.toString()
        )
    }

    /** Ruta de una toma encolada: con [QueuedRecording.sharedWith] es una demo y va a [DEMOS_DIR]. */
    fun objectPath(item: QueuedRecording): String {
        val dir = if (item.sharedWith != null) DEMOS_DIR else RECORDINGS_DIR
        return "users/${item.take.uid}/$dir/${item.id}.m4a"
    }

    /**
     * Metadatos de una toma encolada: los de [customMetadata] mas la tarea y el destinatario si los hay. Las reglas
     * exigen tarea y destinatario juntos en una demo, y una grabacion propia no puede llevar destinatario.
     */
    fun customMetadata(item: QueuedRecording): Map<String, String> {
        val assignmentId = item.assignmentId
        val sharedWith = item.sharedWith
        require(assignmentId == null || ASSIGNMENT_ID.matches(assignmentId)) { "assignmentId fuera de las reglas" }
        require(sharedWith == null || SHARED_WITH.matches(sharedWith)) { "sharedWith fuera de las reglas" }
        require(sharedWith == null || assignmentId != null) { "una demo necesita su tarea" }
        return customMetadata(item.take) +
            listOfNotNull(assignmentId?.let { ASSIGNMENT_KEY to it }, sharedWith?.let { SHARED_WITH_KEY to it })
    }
}
