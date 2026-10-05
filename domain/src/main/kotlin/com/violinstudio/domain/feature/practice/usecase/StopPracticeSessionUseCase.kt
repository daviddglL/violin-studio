package com.violinstudio.domain.feature.practice.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.practice.failure.PracticeFailure
import com.violinstudio.domain.feature.practice.model.PracticeDraft
import com.violinstudio.domain.feature.practice.model.PracticeRules
import com.violinstudio.domain.feature.practice.repository.PracticeLogRepository
import com.violinstudio.domain.feature.practice.repository.RunningSessionStore
import java.time.Clock
import java.time.Duration
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/** [draft] es lo escrito; [clamped] avisa de que la duración se recortó a 12 h. */
data class StoppedPractice(val draft: PracticeDraft, val clamped: Boolean)

/**
 * Para la sesión: duración = reloj - inicio (reloj de pared: el inicio persiste entre procesos). < 1 s ->
 * `TooShort` (se descarta la sesión en curso); > 12 h se recorta a 43 200 s. Si la validación de notas o la
 * escritura fallan, la sesión en curso se conserva.
 */
class StopPracticeSessionUseCase(
    private val auth: AuthRepository,
    private val store: RunningSessionStore,
    private val repo: PracticeLogRepository,
    private val clock: Clock,
    private val newId: () -> String
) {
    @Inject
    constructor(
        auth: AuthRepository,
        store: RunningSessionStore,
        repo: PracticeLogRepository,
        clock: Clock
    ) : this(auth, store, repo, clock, { UUID.randomUUID().toString() })

    suspend operator fun invoke(notes: String? = null): Result<StoppedPractice> {
        val uid = auth.authUser.first()?.uid ?: return Result.failure(PracticeFailure.NoSession)
        val running = store.observe(uid).first() ?: return Result.failure(PracticeFailure.NotRunning)
        val now = clock.instant()
        val elapsed = Duration.between(running.startedAt, now).seconds
        if (elapsed < PracticeRules.MIN_DURATION_SEC) {
            store.clear(uid)
            return Result.failure(PracticeFailure.TooShort)
        }
        val clamped = elapsed > PracticeRules.MAX_DURATION_SEC
        val seconds = minOf(elapsed, PracticeRules.MAX_DURATION_SEC.toLong()).toInt()
        val draft = PracticeDraft.create(newId(), running.startedAt, seconds, running.instrument, notes, now)
            .getOrElse { return Result.failure(it) }
        repo.create(uid, draft).onFailure { return Result.failure(it) }
        store.clear(uid)
        return Result.success(StoppedPractice(draft, clamped))
    }
}
