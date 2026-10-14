package com.violinstudio.domain.feature.practice.usecase

import com.violinstudio.domain.feature.practice.model.PracticeSession
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

/**
 * Segundos practicados en la semana ISO actual (lunes 00:00 a lunes siguiente) sumados sobre el historial
 * (200 sesiones). El [clock] solo aporta el instante; la zona sale de [zone], que se lee en cada emisión (el
 * dispositivo puede cambiar de zona). Una sesión que cruza el límite de semana cuenta en la de su `startedAt`.
 * Se recalcula cuando cambia el historial y al cruzar el lunes a las 00:00 (ver [weeklyTotalFlow]).
 */
class WeeklyPracticeTotalUseCase(
    private val history: ObservePracticeHistoryUseCase,
    private val clock: Clock,
    private val zone: () -> ZoneId
) {
    @Inject
    constructor(history: ObservePracticeHistoryUseCase, clock: Clock) :
        this(history, clock, { ZoneId.systemDefault() })

    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<Int> = history().flatMapLatest { sessions -> sessions.weeklyTotalFlow(clock, zone) }
}

/** Segundos de las sesiones cuyo `startedAt` cae en la semana ISO (lunes a lunes) de [now] en [zone]. */
fun List<PracticeSession>.weeklyTotalSec(now: Instant, zone: ZoneId): Int {
    val monday = now.atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val start = monday.atStartOfDay(zone).toInstant()
    val end = monday.plusWeeks(1).atStartOfDay(zone).toInstant()
    return filter { it.startedAt >= start && it.startedAt < end }.sumOf { it.durationSec }
}

/** Inicio (lunes 00:00 en [zone]) de la semana ISO siguiente a la de este instante. */
fun Instant.nextWeekStart(zone: ZoneId): Instant =
    atZone(zone).toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atStartOfDay(zone).toInstant()

/** Margen por el que `delay` (reloj monotónico) puede despertar antes que el reloj de pared. */
private val EARLY_WAKE_TOLERANCE: Duration = Duration.ofSeconds(5)

/**
 * Total semanal de estas sesiones que se vuelve a emitir al cruzar el lunes a las 00:00 sin nuevo historial
 * (el total de la semana que acaba pasa a ser el de la nueva). El reloj y la zona se leen en cada emisión. Si tras
 * esperar el [clock] no ha llegado al límite (reloj fijo en tests o salto del reloj del sistema) deja de
 * re-armar: la siguiente emisión del historial lo rearma; así un reloj que no avanza no produce un bucle.
 */
fun List<PracticeSession>.weeklyTotalFlow(clock: Clock, zone: () -> ZoneId): Flow<Int> = flow {
    while (true) {
        val now = clock.instant()
        val currentZone = zone()
        emit(weeklyTotalSec(now, currentZone))
        val next = now.nextWeekStart(currentZone)
        delay(Duration.between(now, next).toMillis().coerceAtLeast(1))
        val after = clock.instant()
        if (after < next && Duration.between(after, next) > EARLY_WAKE_TOLERANCE) return@flow
    }
}
