package com.violinstudio.domain.feature.practice.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.practice.failure.PracticeFailure
import com.violinstudio.domain.feature.practice.model.RunningSession
import com.violinstudio.domain.feature.practice.repository.RunningSessionStore
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.profile.repository.ProfileRepository
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock

/**
 * Inicia la sesión: instrumento del perfil salvo que se indique otro (sin modificar el perfil). Genera el id
 * (UUID) que llevará el doc final. Serializado con Stop mediante el [PracticeSessionLock] compartido.
 */
class StartPracticeSessionUseCase(
    private val auth: AuthRepository,
    private val profiles: ProfileRepository,
    private val store: RunningSessionStore,
    private val clock: Clock,
    private val lock: PracticeSessionLock,
    private val newId: () -> String
) {
    @Inject
    constructor(
        auth: AuthRepository,
        profiles: ProfileRepository,
        store: RunningSessionStore,
        clock: Clock,
        lock: PracticeSessionLock
    ) : this(auth, profiles, store, clock, lock, { UUID.randomUUID().toString() })

    suspend operator fun invoke(instrument: Instrument? = null): Result<RunningSession> = lock.mutex.withLock {
        guardedStore { run(instrument) }
    }

    private suspend fun run(instrument: Instrument?): Result<RunningSession> {
        val uid = auth.authUser.first()?.uid ?: return Result.failure(PracticeFailure.NoSession)
        if (store.observe(uid).first() != null) return Result.failure(PracticeFailure.AlreadyRunning)
        val chosen = instrument ?: profiles.observe(uid).first()?.instrument
            ?: return Result.failure(PracticeFailure.NoSession)
        val running = RunningSession(newId(), clock.instant(), chosen)
        store.start(uid, running)
        return Result.success(running)
    }
}
