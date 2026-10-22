package com.violinstudio.domain.feature.achievement.model

import com.violinstudio.domain.feature.practice.model.PracticeSession
import com.violinstudio.domain.feature.practice.model.practiceStats
import com.violinstudio.domain.feature.profile.model.Instrument
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AchievementsTest {
    private val now = Instant.parse("2026-10-14T12:00:00Z")

    private fun session(day: Int, sec: Int): PracticeSession {
        val at = "2026-10-%02dT09:00:00Z".format(day)
        return PracticeSession(at, Instant.parse(at), sec, Instrument.VIOLIN, null, false)
    }

    private fun progress(sessions: List<PracticeSession>, lessons: Int = 0) =
        Achievements.of(sessions.practiceStats(now, ZoneOffset.UTC), lessons).associateBy { it.achievement }

    @Test
    fun `sin practica no hay ningun logro y salen todos en orden`() {
        val list = Achievements.of(emptyList<PracticeSession>().practiceStats(now, ZoneOffset.UTC), 0)
        assertEquals(Achievement.entries, list.map { it.achievement })
        assertTrue(list.none { it.unlocked })
        assertTrue(list.all { it.current == 0 })
    }

    @Test
    fun `sesiones, racha y horas desbloquean lo suyo`() {
        // 4 dias seguidos (11 al 14) de 20 min mas una sesion suelta de 30 min: 5 sesiones, racha de 4, 1 h 50 min.
        val sessions = (11..14).map { session(it, 1_200) } + session(2, 1_800)
        val got = progress(sessions)
        assertTrue(got.getValue(Achievement.FIRST_SESSION).unlocked)
        assertEquals(5, got.getValue(Achievement.SESSIONS_10).current)
        assertFalse(got.getValue(Achievement.SESSIONS_10).unlocked)
        assertTrue(got.getValue(Achievement.STREAK_3).unlocked)
        assertEquals(3, got.getValue(Achievement.STREAK_3).current)
        assertEquals(4, got.getValue(Achievement.STREAK_7).current)
        assertTrue(got.getValue(Achievement.HOURS_1).unlocked)
        assertEquals(1, got.getValue(Achievement.HOURS_10).current)
    }

    @Test
    fun `las lecciones cuentan hasta el curriculo entero`() {
        val some = progress(emptyList(), lessons = 3)
        assertTrue(some.getValue(Achievement.FIRST_LESSON).unlocked)
        assertEquals(3, some.getValue(Achievement.ALL_LESSONS).current)
        assertFalse(some.getValue(Achievement.ALL_LESSONS).unlocked)
        val all = progress(emptyList(), lessons = Achievement.ALL_LESSONS.target)
        assertTrue(all.getValue(Achievement.ALL_LESSONS).unlocked)
        assertEquals(12, Achievement.ALL_LESSONS.target)
    }
}
