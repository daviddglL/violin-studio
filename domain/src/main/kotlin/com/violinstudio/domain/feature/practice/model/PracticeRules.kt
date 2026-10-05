package com.violinstudio.domain.feature.practice.model

import com.violinstudio.domain.feature.practice.failure.PracticeFailure

object PracticeRules {
    const val MIN_DURATION_SEC = 1
    const val MAX_DURATION_SEC = 43_200
    const val NOTES_MAX = 500
    const val ID_MAX = 64
    const val HISTORY_LIMIT = 200

    /** Recorta; vacío -> `null`; más de [NOTES_MAX] unidades UTF-16 (`String.length`, como las reglas) falla. */
    fun notes(raw: String?): Result<String?> {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() }
        return if (trimmed != null && trimmed.length > NOTES_MAX) {
            Result.failure(PracticeFailure.NotesTooLong)
        } else {
            Result.success(trimmed)
        }
    }
}
