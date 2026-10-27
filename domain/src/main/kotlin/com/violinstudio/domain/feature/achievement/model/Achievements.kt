package com.violinstudio.domain.feature.achievement.model

import com.violinstudio.domain.feature.curriculum.model.Curriculum
import com.violinstudio.domain.feature.practice.model.PracticeStats

/** Lo que mide un logro. */
enum class AchievementMetric { SESSIONS, LONGEST_STREAK_DAYS, PRACTICE_HOURS, LESSONS }

/**
 * Logros de la fase 5, en el orden en que se muestran. Se calculan en el dispositivo a partir de las estadisticas y del
 * curriculo: no se guardan ni se envian, y el profesor no los ve.
 */
enum class Achievement(val metric: AchievementMetric, val target: Int) {
    FIRST_SESSION(AchievementMetric.SESSIONS, 1),
    SESSIONS_10(AchievementMetric.SESSIONS, 10),
    SESSIONS_50(AchievementMetric.SESSIONS, 50),
    STREAK_3(AchievementMetric.LONGEST_STREAK_DAYS, 3),
    STREAK_7(AchievementMetric.LONGEST_STREAK_DAYS, 7),
    STREAK_30(AchievementMetric.LONGEST_STREAK_DAYS, 30),
    HOURS_1(AchievementMetric.PRACTICE_HOURS, 1),
    HOURS_10(AchievementMetric.PRACTICE_HOURS, 10),
    FIRST_LESSON(AchievementMetric.LESSONS, 1),
    ALL_LESSONS(AchievementMetric.LESSONS, Curriculum.lessons.size)
}

/** Avance de un logro: [current] nunca pasa de [Achievement.target]. */
data class AchievementProgress(val achievement: Achievement, val current: Int) {
    val unlocked: Boolean get() = current >= achievement.target
}

object Achievements {
    /**
     * Avance de cada logro con [stats] y [lessonsDone] lecciones hechas. Con mas de `HISTORY_LIMIT` sesiones, los de
     * sesiones y horas usan los totales del servidor; sin conexion pueden quedarse cortos hasta que vuelva.
     */
    fun of(stats: PracticeStats, lessonsDone: Int): List<AchievementProgress> = Achievement.entries.map {
        val value = when (it.metric) {
            AchievementMetric.SESSIONS -> stats.sessionCount
            AchievementMetric.LONGEST_STREAK_DAYS -> stats.longestStreakDays
            AchievementMetric.PRACTICE_HOURS -> stats.totalSec / SECONDS_PER_HOUR
            AchievementMetric.LESSONS -> lessonsDone
        }
        AchievementProgress(it, value.coerceIn(0, it.target))
    }

    private const val SECONDS_PER_HOUR = 3_600
}
