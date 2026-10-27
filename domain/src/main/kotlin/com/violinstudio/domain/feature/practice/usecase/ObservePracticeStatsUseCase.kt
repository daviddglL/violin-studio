package com.violinstudio.domain.feature.practice.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.practice.model.PracticeRules
import com.violinstudio.domain.feature.practice.model.PracticeStats
import com.violinstudio.domain.feature.practice.model.practiceStats
import com.violinstudio.domain.feature.practice.repository.PracticeLogRepository
import java.time.Clock
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest

/**
 * Estadisticas del historial del usuario con sesion; se recalculan con cada cambio del historial. El instante y la zona
 * se leen en cada emision. No se re-emite al cambiar de dia sin historial nuevo: la pantalla se abre y se cierra.
 *
 * Con el historial lleno ([PracticeRules.HISTORY_LIMIT]) emite primero lo calculado con el historial y despues, si el
 * servidor responde, lo mismo con los totales de todo el historial: una consulta de agregacion por cambio, solo para
 * quien tiene mas de 200 sesiones.
 */
class ObservePracticeStatsUseCase(
    private val history: ObservePracticeHistoryUseCase,
    private val auth: AuthRepository,
    private val repo: PracticeLogRepository,
    private val clock: Clock,
    private val zone: () -> ZoneId
) {
    @Inject
    constructor(
        history: ObservePracticeHistoryUseCase,
        auth: AuthRepository,
        repo: PracticeLogRepository,
        clock: Clock
    ) : this(history, auth, repo, clock, { ZoneId.systemDefault() })

    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<PracticeStats> = history().transformLatest { sessions ->
        val windowed = sessions.practiceStats(clock.instant(), zone())
        emit(windowed)
        if (!windowed.truncated) return@transformLatest
        val uid = auth.authUser.first()?.uid ?: return@transformLatest
        val totals = repo.totals(uid) ?: return@transformLatest
        emit(sessions.practiceStats(clock.instant(), zone(), totals = totals))
    }
}
