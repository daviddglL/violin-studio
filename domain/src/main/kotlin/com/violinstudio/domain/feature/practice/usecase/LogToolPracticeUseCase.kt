package com.violinstudio.domain.feature.practice.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.practice.failure.PracticeFailure
import com.violinstudio.domain.feature.practice.model.PracticeDraft
import com.violinstudio.domain.feature.practice.model.ToolPractice
import com.violinstudio.domain.feature.practice.repository.PracticeLogRepository
import com.violinstudio.domain.feature.practice.repository.RunningSessionStore
import com.violinstudio.domain.feature.profile.repository.ProfileRepository
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock

/**
 * Guarda como sesion la practica hecha con una herramienta, solo cuando el usuario lo pide desde la pantalla. Con una
 * sesion manual en curso falla `AlreadyRunning`: ese tiempo ya se esta registrando. Instrumento del perfil, sin notas.
 */
class LogToolPracticeUseCase(
    private val auth: AuthRepository,
    private val profiles: ProfileRepository,
    private val store: RunningSessionStore,
    private val repo: PracticeLogRepository,
    private val clock: Clock,
    private val lock: PracticeSessionLock,
    private val newId: () -> String
) {
    @Inject
    constructor(
        auth: AuthRepository,
        profiles: ProfileRepository,
        store: RunningSessionStore,
        repo: PracticeLogRepository,
        clock: Clock,
        lock: PracticeSessionLock
    ) : this(auth, profiles, store, repo, clock, lock, { UUID.randomUUID().toString() })

    suspend operator fun invoke(practice: ToolPractice): Result<PracticeDraft> = lock.mutex.withLock {
        guardedStore { run(practice) }
    }

    private suspend fun run(practice: ToolPractice): Result<PracticeDraft> {
        val uid = auth.authUser.first()?.uid ?: return Result.failure(PracticeFailure.NoSession)
        if (store.observe(uid).first() != null) return Result.failure(PracticeFailure.AlreadyRunning)
        val instrument = profiles.observe(uid).first()?.instrument ?: return Result.failure(PracticeFailure.NoSession)
        val now = clock.instant()
        val draft = PracticeDraft.create(newId(), practice.startedAt, practice.durationSec, instrument, null, now)
            .getOrElse { return Result.failure(it) }
        return repo.create(uid, draft).map { draft }
    }
}
