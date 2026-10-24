package com.violinstudio.domain.feature.practice.model

import com.violinstudio.domain.feature.curriculum.model.Lesson

/** Consejo de practica calculado en el dispositivo a partir del historial y del curriculo. Los textos viven en la UI. */
sealed interface PracticeTip {
    /** Aun no hay ninguna sesion. */
    data object FirstSession : PracticeTip

    /** Hay historial pero nada en los ultimos 7 dias. */
    data object ComeBack : PracticeTip

    /** Racha de [days] dias que se pierde si hoy no se practica. */
    data class KeepStreak(val days: Int) : PracticeTip

    /** Faltan [minutes] para igualar la semana pasada. */
    data class CatchUpWeek(val minutes: Int) : PracticeTip

    /** Las sesiones duran [averageMinutes] de media, por debajo de lo recomendado. */
    data class LongerSessions(val averageMinutes: Int) : PracticeTip

    /** Siguiente leccion del curriculo, con su practica diaria sugerida. */
    data class NextLesson(val lesson: Lesson) : PracticeTip
}

/**
 * Reglas de los consejos, de mas a menos urgente y como mucho [MAX_TIPS]: primero la constancia (primera sesion, volver
 * o mantener la racha), luego el volumen (semana y duracion) y al final que toca en el curriculo.
 */
object PracticeTips {
    const val MAX_TIPS = 3
    const val MIN_STREAK_DAYS = 2
    const val SHORT_SESSION_SEC = 10 * 60
    const val MIN_SESSIONS_FOR_AVERAGE = 3

    fun of(stats: PracticeStats, next: Lesson?): List<PracticeTip> = buildList {
        val practicedToday = (stats.last7Days.lastOrNull()?.seconds ?: 0) > 0
        when {
            stats.sessionCount == 0 -> add(PracticeTip.FirstSession)
            stats.last7Days.all { it.seconds == 0 } -> add(PracticeTip.ComeBack)
            !practicedToday && stats.currentStreakDays >= MIN_STREAK_DAYS ->
                add(PracticeTip.KeepStreak(stats.currentStreakDays))
        }
        if (stats.previousWeekSec > 0 && stats.weekSec < stats.previousWeekSec) {
            add(PracticeTip.CatchUpWeek(ceilMinutes(stats.previousWeekSec - stats.weekSec)))
        }
        if (stats.sessionCount >= MIN_SESSIONS_FOR_AVERAGE && stats.averageSessionSec < SHORT_SESSION_SEC) {
            add(PracticeTip.LongerSessions(stats.averageSessionSec / 60))
        }
        next?.let { add(PracticeTip.NextLesson(it)) }
    }.take(MAX_TIPS)

    private fun ceilMinutes(seconds: Int) = (seconds + 59) / 60
}
