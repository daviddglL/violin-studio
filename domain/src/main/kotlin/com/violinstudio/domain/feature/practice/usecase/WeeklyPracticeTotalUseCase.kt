package com.violinstudio.domain.feature.practice.usecase

import java.time.Clock
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Segundos practicados en la semana ISO actual (lunes 00:00 a lunes siguiente) sumados sobre el historial
 * (200 sesiones). El [clock] solo aporta el instante; la zona sale de [zone], que se lee en cada emisión (el
 * dispositivo puede cambiar de zona). Una sesión que cruza el límite de semana cuenta en la de su `startedAt`.
 * Solo se recalcula cuando cambia el historial: la UI debe volver a lanzarlo el lunes a las 00:00.
 */
class WeeklyPracticeTotalUseCase(
    private val history: ObservePracticeHistoryUseCase,
    private val clock: Clock,
    private val zone: () -> ZoneId
) {
    @Inject
    constructor(history: ObservePracticeHistoryUseCase, clock: Clock) :
        this(history, clock, { ZoneId.systemDefault() })

    operator fun invoke(): Flow<Int> = history().map { sessions ->
        val zone = zone()
        val today = clock.instant().atZone(zone).toLocalDate()
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val start = monday.atStartOfDay(zone).toInstant()
        val end = monday.plusWeeks(1).atStartOfDay(zone).toInstant()
        sessions.filter { it.startedAt >= start && it.startedAt < end }.sumOf { it.durationSec }
    }
}
