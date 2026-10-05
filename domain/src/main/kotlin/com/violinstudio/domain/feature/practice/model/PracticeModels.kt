package com.violinstudio.domain.feature.practice.model

import com.violinstudio.domain.feature.practice.failure.PracticeFailure
import com.violinstudio.domain.feature.profile.model.Instrument
import java.time.Instant

/** Sesión leída del historial. [pendingSync]: escrita en local y aún no confirmada por el servidor. */
data class PracticeSession(
    val id: String,
    val startedAt: Instant,
    val durationSec: Int,
    val instrument: Instrument,
    val notes: String?,
    val pendingSync: Boolean
) {
    override fun toString(): String = "PracticeSession(durationSec=$durationSec, instrument=$instrument)"
}

/** Sesión en curso con su [id] (UUID, también el del doc final), persistida en local por uid. */
data class RunningSession(val id: String, val startedAt: Instant, val instrument: Instrument)

/** Sesión validada lista para escribir. Solo se crea con [create]; las notas ya van recortadas. */
@ConsistentCopyVisibility
data class PracticeDraft private constructor(
    val id: String,
    val startedAt: Instant,
    val durationSec: Int,
    val instrument: Instrument,
    val notes: String?
) {
    override fun toString(): String = "PracticeDraft(durationSec=$durationSec, instrument=$instrument)"

    companion object {
        /** [id] es un UUID generado en cliente (1..64); [now] rechaza un `startedAt` futuro. */
        fun create(
            id: String,
            startedAt: Instant,
            durationSec: Int,
            instrument: Instrument,
            notes: String?,
            now: Instant
        ): Result<PracticeDraft> {
            val failure = when {
                id.isEmpty() || id.length > PracticeRules.ID_MAX -> PracticeFailure.InvalidId
                durationSec !in PracticeRules.MIN_DURATION_SEC..PracticeRules.MAX_DURATION_SEC ->
                    PracticeFailure.InvalidDuration
                startedAt.isAfter(now) -> PracticeFailure.InvalidStart
                else -> null
            }
            if (failure != null) return Result.failure(failure)
            return PracticeRules.notes(notes).map { PracticeDraft(id, startedAt, durationSec, instrument, it) }
        }
    }
}
