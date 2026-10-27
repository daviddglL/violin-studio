package com.violinstudio.domain.feature.practice.model

import com.violinstudio.domain.feature.profile.model.Instrument
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PracticeStatsTest {
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-10-14T12:00:00Z") // miercoles; semana ISO desde el lunes 12

    private fun session(startedAt: String, sec: Int) =
        PracticeSession(startedAt, Instant.parse(startedAt), sec, Instrument.VIOLIN, null, false)

    @Test
    fun `sin sesiones todo es cero y hay 7 dias vacios`() {
        val stats = emptyList<PracticeSession>().practiceStats(now, zone)
        assertEquals(0, stats.weekSec)
        assertEquals(0, stats.currentStreakDays)
        assertEquals(0, stats.longestStreakDays)
        assertEquals(0, stats.averageSessionSec)
        assertEquals(7, stats.last7Days.size)
        assertTrue(stats.last7Days.all { it.seconds == 0 })
        assertEquals(LocalDate.of(2026, 10, 8), stats.last7Days.first().date)
        assertEquals(LocalDate.of(2026, 10, 14), stats.last7Days.last().date)
        assertFalse(stats.truncated)
    }

    @Test
    fun `semana actual, anterior y ultimos 30 dias`() {
        val stats = listOf(
            session("2026-10-14T09:00:00Z", 600),
            session("2026-10-12T00:00:00Z", 300),
            session("2026-10-11T23:59:59Z", 900),
            session("2026-10-05T08:00:00Z", 100),
            session("2026-09-15T08:00:00Z", 50),
            session("2026-09-14T08:00:00Z", 7)
        ).practiceStats(now, zone)
        assertEquals(900, stats.weekSec)
        assertEquals(1_000, stats.previousWeekSec)
        assertEquals(1_950, stats.last30DaysSec)
        assertEquals(1_957, stats.totalSec)
        assertEquals(6, stats.sessionCount)
        assertEquals(1_957 / 6, stats.averageSessionSec)
    }

    @Test
    fun `los ultimos 7 dias suman las sesiones de cada dia`() {
        val stats = listOf(
            session("2026-10-14T09:00:00Z", 600),
            session("2026-10-14T18:00:00Z", 60),
            session("2026-10-08T08:00:00Z", 30),
            session("2026-10-07T08:00:00Z", 999)
        ).practiceStats(now, zone)
        assertEquals(listOf(30, 0, 0, 0, 0, 0, 660), stats.last7Days.map { it.seconds })
    }

    @Test
    fun `la racha actual cuenta hasta hoy o, si hoy no hay practica, hasta ayer`() {
        val days = listOf("2026-10-11", "2026-10-12", "2026-10-13").map { session("${it}T10:00:00Z", 60) }
        assertEquals(3, days.practiceStats(now, zone).currentStreakDays)
        assertEquals(4, (days + session("2026-10-14T08:00:00Z", 60)).practiceStats(now, zone).currentStreakDays)
        assertEquals(0, listOf(session("2026-10-12T10:00:00Z", 60)).practiceStats(now, zone).currentStreakDays)
    }

    @Test
    fun `la racha mas larga puede estar en el pasado`() {
        val old = listOf("2026-09-01", "2026-09-02", "2026-09-03", "2026-09-04").map { session("${it}T10:00:00Z", 60) }
        val recent = listOf("2026-10-13", "2026-10-14").map { session("${it}T10:00:00Z", 60) }
        val stats = (old + recent).practiceStats(now, zone)
        assertEquals(4, stats.longestStreakDays)
        assertEquals(2, stats.currentStreakDays)
    }

    @Test
    fun `el dia sale de la zona del dispositivo`() {
        val madrid = ZoneId.of("Europe/Madrid")
        // 23:30 UTC del dia 13 son las 01:30 del 14 en Madrid.
        val stats = listOf(session("2026-10-13T23:30:00Z", 60)).practiceStats(now, madrid)
        assertEquals(60, stats.last7Days.last().seconds)
        assertEquals(1, stats.currentStreakDays)
    }

    @Test
    fun `avisa cuando el historial llega al limite`() {
        val many = (1..3).map { session("2026-10-1${it}T10:00:00Z", 60) }
        assertTrue(many.practiceStats(now, zone, limit = 3).truncated)
        assertFalse(many.practiceStats(now, zone, limit = 4).truncated)
    }

    @Test
    fun `con los totales del servidor el recuento es exacto y suma lo pendiente`() {
        val pending = session("2026-10-14T10:00:00Z", 120).copy(pendingSync = true)
        val many = listOf(pending) + (1..2).map { session("2026-10-1${it}T10:00:00Z", 60) }
        val stats = many.practiceStats(now, zone, limit = 3, totals = PracticeTotals(500, 30_000L))
        assertFalse(stats.truncated)
        assertEquals(501, stats.sessionCount)
        assertEquals(30_120, stats.totalSec)
        assertEquals(60, stats.averageSessionSec)
        assertEquals(180, stats.weekSec)
    }
}
