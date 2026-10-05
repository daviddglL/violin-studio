package com.violinstudio.domain.feature.practice.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.practice.repository.PracticeLogRepository
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Segundos practicados en la semana ISO actual (lunes 00:00 a lunes siguiente, zona del [Clock] inyectado),
 * sumados sobre el historial (200 sesiones). El [Clock] debe llevar la zona del dispositivo.
 */
class WeeklyPracticeTotalUseCase @Inject constructor(
    auth: AuthRepository,
    repo: PracticeLogRepository,
    private val clock: Clock
) {
    private val history = ObservePracticeHistoryUseCase(auth, repo)

    operator fun invoke(): Flow<Int> = history().map { sessions ->
        val monday = LocalDate.now(clock).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val start = monday.atStartOfDay(clock.zone).toInstant()
        val end = monday.plusWeeks(1).atStartOfDay(clock.zone).toInstant()
        sessions.filter { it.startedAt >= start && it.startedAt < end }.sumOf { it.durationSec }
    }
}
