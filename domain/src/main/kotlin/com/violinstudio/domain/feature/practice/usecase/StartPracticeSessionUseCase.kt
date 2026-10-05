package com.violinstudio.domain.feature.practice.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.practice.failure.PracticeFailure
import com.violinstudio.domain.feature.practice.model.RunningSession
import com.violinstudio.domain.feature.practice.repository.RunningSessionStore
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.profile.repository.ProfileRepository
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/** Inicia la sesión: instrumento del perfil salvo que se indique otro (sin modificar el perfil). */
class StartPracticeSessionUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val profiles: ProfileRepository,
    private val store: RunningSessionStore,
    private val clock: Clock
) {
    suspend operator fun invoke(instrument: Instrument? = null): Result<RunningSession> {
        val uid = auth.authUser.first()?.uid ?: return Result.failure(PracticeFailure.NoSession)
        if (store.observe(uid).first() != null) return Result.failure(PracticeFailure.AlreadyRunning)
        val chosen = instrument ?: profiles.observe(uid).first()?.instrument
            ?: return Result.failure(PracticeFailure.NoSession)
        val running = RunningSession(clock.instant(), chosen)
        store.start(uid, running)
        return Result.success(running)
    }
}
