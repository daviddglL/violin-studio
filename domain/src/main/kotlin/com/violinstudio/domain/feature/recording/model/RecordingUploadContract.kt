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

    /** Metadato obligatorio: la `recordingConsentEpoch` capturada al EMPEZAR a grabar (decimal canonico). */
    const val EPOCH_KEY = "recordingConsentEpoch"
    const val KIND_KEY = "kind"
    const val DURATION_KEY = "durationMs"
    const val KIND_AUDIO = "audio"

    private const val MAX_EPOCH = 999_999_999L
    private const val MAX_DURATION_MS = 999_999L

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
}
