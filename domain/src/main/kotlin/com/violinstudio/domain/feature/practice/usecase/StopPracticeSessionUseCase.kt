package com.violinstudio.domain.feature.practice.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.practice.failure.PracticeFailure
import com.violinstudio.domain.feature.practice.model.PracticeDraft
import com.violinstudio.domain.feature.practice.model.PracticeRules
import com.violinstudio.domain.feature.practice.repository.PracticeLogRepository
import com.violinstudio.domain.feature.practice.repository.RunningSessionStore
import java.time.Clock
import java.time.Duration
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock

/**
 * [draft] es lo calculado; [clamped] avisa de que la duración se recortó a 12 h; [alreadySaved] indica que el
 * doc ya existía (el proceso murió entre escribir y limpiar) y no se volvió a escribir.
 */
data class StoppedPractice(val draft: PracticeDraft, val clamped: Boolean, val alreadySaved: Boolean = false)

/**
 * Para la sesión: duración = reloj - inicio (reloj de pared: el inicio persiste entre procesos). < 1 s (o reloj
 * anterior al inicio) -> `TooShort` (se descarta la sesión en curso); > 12 h se recorta a 43 200 s. Si la
 * validación de notas o la escritura fallan, la sesión en curso se conserva. El doc usa el id de la sesión en
 * curso: si ya existe, solo se limpia el almacén. Serializado con Start mediante el [PracticeSessionLock].
 */
class StopPracticeSessionUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val store: RunningSessionStore,
    private val repo: PracticeLogRepository,
    private val clock: Clock,
    private val lock: PracticeSessionLock
) {
    suspend operator fun invoke(notes: String? = null): Result<StoppedPractice> = lock.mutex.withLock {
        guardedStore { run(notes) }
    }

    private suspend fun run(notes: String?): Result<StoppedPractice> {
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
        val draft = PracticeDraft.create(running.id, running.startedAt, seconds, running.instrument, notes, now)
            .getOrElse { return Result.failure(it) }
        val alreadySaved = repo.exists(uid, running.id)
        if (!alreadySaved) repo.create(uid, draft).onFailure { return Result.failure(it) }
        store.clear(uid)
        return Result.success(StoppedPractice(draft, clamped, alreadySaved))
    }
}
