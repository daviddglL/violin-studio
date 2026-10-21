package com.violinstudio.domain.feature.practice.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Segundos practicados un dia natural en la zona del dispositivo. */
data class DayTotal(val date: LocalDate, val seconds: Int)

/**
 * Resumen del historial para la pantalla de estadisticas. Se calcula sobre lo que devuelve el historial (como mucho
 * [PracticeRules.HISTORY_LIMIT] sesiones): con [truncated] los totales globales y la racha mas larga pueden quedarse
 * cortos, y la UI lo avisa.
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

/** Estadisticas de estas sesiones en [now] y [zone]. Cada sesion cuenta entera en el dia de su `startedAt`. */
fun List<PracticeSession>.practiceStats(
    now: Instant,
    zone: ZoneId,
    limit: Int = PracticeRules.HISTORY_LIMIT
): PracticeStats {
    val today = now.atZone(zone).toLocalDate()
    val byDay: Map<LocalDate, Int> = groupBy { it.startedAt.atZone(zone).toLocalDate() }
        .mapValues { (_, list) -> list.sumOf { it.durationSec } }
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    fun sumBetween(fromInclusive: LocalDate, toExclusive: LocalDate) =
        byDay.filterKeys { it >= fromInclusive && it < toExclusive }.values.sum()
    val total = sumOf { it.durationSec }
    return PracticeStats(
        weekSec = sumBetween(monday, monday.plusWeeks(1)),
        previousWeekSec = sumBetween(monday.minusWeeks(1), monday),
        last30DaysSec = sumBetween(today.minusDays(29), today.plusDays(1)),
        last7Days = (6 downTo 0).map { back -> today.minusDays(back.toLong()).let { DayTotal(it, byDay[it] ?: 0) } },
        currentStreakDays = currentStreak(byDay.keys, today),
        longestStreakDays = longestStreak(byDay.keys),
        sessionCount = size,
        totalSec = total,
        averageSessionSec = if (isEmpty()) 0 else total / size,
        truncated = size >= limit
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
