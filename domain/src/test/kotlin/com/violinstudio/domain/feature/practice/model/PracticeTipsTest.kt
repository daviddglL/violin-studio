package com.violinstudio.domain.feature.practice.model

import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.curriculum.model.Curriculum
import com.violinstudio.domain.feature.curriculum.usecase.ObserveCurriculumUseCase
import com.violinstudio.domain.feature.practice.usecase.ObservePracticeHistoryUseCase
import com.violinstudio.domain.feature.practice.usecase.ObservePracticeStatsUseCase
import com.violinstudio.domain.feature.practice.usecase.ObservePracticeTipsUseCase
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.verifiedUser
import com.violinstudio.domain.testing.FakeLessonProgressRepository
import com.violinstudio.domain.testing.FakePracticeLogRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PracticeTipsTest {
    private val today = LocalDate.parse("2026-10-14")
    private val lesson = Curriculum.lessons[2]

    /** Estadisticas con [days] = segundos de los ultimos 7 dias (de hace 6 dias a hoy). */
    private fun stats(
        days: List<Int> = List(7) { 0 },
        streak: Int = 0,
        week: Int = 0,
        previousWeek: Int = 0,
        sessions: Int = days.count { it > 0 },
        average: Int = 900
    ) = PracticeStats(
        weekSec = week,
        previousWeekSec = previousWeek,
        last30DaysSec = days.sum(),
        last7Days = days.mapIndexed { i, s -> DayTotal(today.minusDays(6L - i), s) },
        currentStreakDays = streak,
        longestStreakDays = streak,
        sessionCount = sessions,
        totalSec = days.sum(),
        averageSessionSec = average,
        truncated = false
    )

    @Test
    fun `sin sesiones invita a la primera y a la siguiente leccion`() {
        assertEquals(
            listOf(PracticeTip.FirstSession, PracticeTip.NextLesson(lesson)),
            PracticeTips.of(stats(sessions = 0, average = 0), lesson)
        )
    }

    @Test
    fun `con historial pero una semana sin practicar invita a volver`() {
        assertEquals(listOf(PracticeTip.ComeBack), PracticeTips.of(stats(sessions = 5), null))
    }

    @Test
    fun `la racha solo se avisa si hoy aun no se practico y es de 2 dias o mas`() {
        val streak = stats(days = listOf(0, 0, 0, 0, 600, 600, 0), streak = 2)
        assertEquals(listOf(PracticeTip.KeepStreak(2)), PracticeTips.of(streak, null))
        val done = stats(days = listOf(0, 0, 0, 0, 600, 600, 600), streak = 3)
        assertEquals(emptyList<PracticeTip>(), PracticeTips.of(done, null))
        val short = stats(days = listOf(0, 0, 0, 0, 0, 600, 0), streak = 1)
        assertEquals(emptyList<PracticeTip>(), PracticeTips.of(short, null))
    }

    @Test
    fun `semana por debajo de la pasada y sesiones cortas, como mucho 3 consejos`() {
        val s = stats(
            days = listOf(0, 0, 0, 0, 300, 300, 0),
            streak = 2,
            week = 600,
            previousWeek = 1801,
            sessions = 4,
            average = 300
        )
        assertEquals(
            listOf(PracticeTip.KeepStreak(2), PracticeTip.CatchUpWeek(21), PracticeTip.LongerSessions(5)),
            PracticeTips.of(s, lesson)
        )
    }

    @Test
    fun `la duracion media no se juzga con menos de 3 sesiones`() {
        val s = stats(days = listOf(0, 0, 0, 0, 0, 120, 120), streak = 2, average = 120)
        assertEquals(listOf(PracticeTip.NextLesson(lesson)), PracticeTips.of(s, lesson))
    }

    @Test
    fun `el caso de uso combina historial y curriculo`() = runTest {
        val auth = FakeAuthRepository().apply { user = verifiedUser }
        val log = FakePracticeLogRepository()
        val progress = FakeLessonProgressRepository()
        log.seed(
            verifiedUser.uid,
            PracticeSession("a", Instant.parse("2026-10-13T09:00:00Z"), 600, Instrument.VIOLIN, null, false),
            PracticeSession("b", Instant.parse("2026-10-12T09:00:00Z"), 600, Instrument.VIOLIN, null, false)
        )
        progress.seed(verifiedUser.uid, "b1_posture")
        val stats = ObservePracticeStatsUseCase(
            ObservePracticeHistoryUseCase(auth, log),
            Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), ZoneOffset.UTC)
        ) { ZoneOffset.UTC }
        val tips = ObservePracticeTipsUseCase(stats, ObserveCurriculumUseCase(auth, progress))().first()
        assertEquals(listOf(PracticeTip.KeepStreak(2), PracticeTip.NextLesson(Curriculum.lessons[1])), tips)
    }
}
