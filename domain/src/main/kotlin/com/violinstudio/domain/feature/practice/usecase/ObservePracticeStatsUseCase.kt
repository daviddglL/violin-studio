package com.violinstudio.domain.feature.practice.usecase

import com.violinstudio.domain.feature.practice.model.PracticeStats
import com.violinstudio.domain.feature.practice.model.practiceStats
import java.time.Clock
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Estadisticas del historial del usuario con sesion; se recalculan con cada cambio del historial. El instante y la zona
 * se leen en cada emision. No se re-emite al cambiar de dia sin historial nuevo: la pantalla se abre y se cierra.
 */
class ObservePracticeStatsUseCase(
    private val history: ObservePracticeHistoryUseCase,
    private val clock: Clock,
    private val zone: () -> ZoneId
) {
    @Inject
    constructor(history: ObservePracticeHistoryUseCase, clock: Clock) :
        this(history, clock, { ZoneId.systemDefault() })

    operator fun invoke(): Flow<PracticeStats> = history().map { it.practiceStats(clock.instant(), zone()) }
}
