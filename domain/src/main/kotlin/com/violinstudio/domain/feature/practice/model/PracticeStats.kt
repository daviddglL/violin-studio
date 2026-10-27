package com.violinstudio.domain.feature.practice.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Segundos practicados un dia natural en la zona del dispositivo. */
data class DayTotal(val date: LocalDate, val seconds: Int)

/**
 * Totales de todo el historial segun el servidor (consulta de agregacion): no incluyen las escrituras aun en cola.
 */
data class PracticeTotals(val sessionCount: Int, val totalSec: Long)

/**
 * Resumen del historial para la pantalla de estadisticas. Se calcula sobre lo que devuelve el historial (como mucho
 * [PracticeRules.HISTORY_LIMIT] sesiones); con el historial lleno, los totales globales salen de [PracticeTotals] si
 * hay conexion. [truncated]: historial lleno y sin totales del servidor, asi que los totales globales pueden quedarse
 * cortos, y la UI lo avisa. La racha mas larga se calcula siempre sobre el historial.
 */
data class PracticeStats(
    val weekSec: Int,
    val previousWeekSec: Int,
    val last30DaysSec: Int,
    /** Los ultimos 7 dias, del mas antiguo a hoy (siempre 7 entradas, con 0 los dias sin practica). */
    val last7Days: List<DayTotal>,
    /** Dias seguidos con practica hasta hoy; si hoy aun no hay, cuenta hasta ayer. */
    val currentStreakDays: Int,
    val longestStreakDays: Int,
    val sessionCount: Int,
    val totalSec: Int,
    val averageSessionSec: Int,
    val truncated: Boolean
)

/**
 * Estadisticas de estas sesiones en [now] y [zone]. Cada sesion cuenta entera en el dia de su `startedAt`. Con
 * [totals] (solo se piden con el historial lleno), el numero de sesiones y el total son los del servidor mas las
 * sesiones de esta lista aun pendientes de confirmar.
 */
fun List<PracticeSession>.practiceStats(
    now: Instant,
    zone: ZoneId,
    limit: Int = PracticeRules.HISTORY_LIMIT,
    totals: PracticeTotals? = null
): PracticeStats {
    val today = now.atZone(zone).toLocalDate()
    val byDay: Map<LocalDate, Int> = groupBy { it.startedAt.atZone(zone).toLocalDate() }
        .mapValues { (_, list) -> list.sumOf { it.durationSec } }
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    fun sumBetween(fromInclusive: LocalDate, toExclusive: LocalDate) =
        byDay.filterKeys { it >= fromInclusive && it < toExclusive }.values.sum()
    val pending = filter { it.pendingSync }
    val count = totals?.let { it.sessionCount + pending.size } ?: size
    val total = if (totals == null) {
        sumOf { it.durationSec }
    } else {
        (totals.totalSec + pending.sumOf { it.durationSec }).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
    return PracticeStats(
        weekSec = sumBetween(monday, monday.plusWeeks(1)),
        previousWeekSec = sumBetween(monday.minusWeeks(1), monday),
        last30DaysSec = sumBetween(today.minusDays(29), today.plusDays(1)),
        last7Days = (6 downTo 0).map { back -> today.minusDays(back.toLong()).let { DayTotal(it, byDay[it] ?: 0) } },
        currentStreakDays = currentStreak(byDay.keys, today),
        longestStreakDays = longestStreak(byDay.keys),
        sessionCount = count,
        totalSec = total,
        averageSessionSec = if (count == 0) 0 else total / count,
        truncated = size >= limit && totals == null
    )
}

private fun currentStreak(days: Set<LocalDate>, today: LocalDate): Int {
    var day = if (today in days) today else today.minusDays(1)
    var streak = 0
    while (day in days) {
        streak++
        day = day.minusDays(1)
    }
    return streak
}

private fun longestStreak(days: Set<LocalDate>): Int {
    var best = 0
    for (day in days) {
        if (day.minusDays(1) in days) continue
        var length = 1
        while (day.plusDays(length.toLong()) in days) length++
        best = maxOf(best, length)
    }
    return best
}
