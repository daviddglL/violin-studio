package com.violinstudio.domain.feature.recording.model

/** Valoracion del profesor (REQ-RVW-01): mismo esquema cerrado que `validReview` de `firestore.rules`. */
enum class Verdict(val wire: String) {
    APPROVED("approved"),
    NEEDS_WORK("needs_work");

    companion object {
        fun fromWire(value: Any?): Verdict? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Revision de una grabacion. [fromTeacher] `false` en una revision anonimizada (el profesor borro su cuenta: el texto
 * se conserva sin autor). El texto es del profesor para el alumno: no sale en `toString`.
 */
data class Review(
    val verdict: Verdict,
    val text: String,
    val updatedAtMs: Long?,
    val fromTeacher: Boolean = true
) {
    override fun toString(): String = "Review(verdict=$verdict)"

    companion object {
        /** `d.text.size() <= 1000` en las reglas. */
        const val MAX_TEXT = 1_000
    }
}

/** Estado del reproductor: una sola grabacion a la vez, en memoria (nunca se escribe en disco). */
sealed interface PlaybackState {
    data object Idle : PlaybackState

    data class Loading(val recordingId: String) : PlaybackState

    data class Playing(val recordingId: String) : PlaybackState

    data class Failed(val recordingId: String) : PlaybackState
}
