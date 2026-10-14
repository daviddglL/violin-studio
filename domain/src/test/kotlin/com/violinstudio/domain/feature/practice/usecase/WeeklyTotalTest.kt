package com.violinstudio.domain.feature.practice.usecase

import com.violinstudio.domain.feature.practice.model.PracticeSession
import com.violinstudio.domain.feature.profile.model.Instrument
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WeeklyTotalTest {
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-10-14T12:00:00Z") // miercoles; semana ISO desde el lunes 12

    private fun session(startedAt: String, sec: Int) =
        PracticeSession(startedAt, Instant.parse(startedAt), sec, Instrument.VIOLIN, null, false)

    @Test
    fun `suma solo las sesiones de la semana ISO actual`() {
        val sessions = listOf(
            session("2026-10-14T09:00:00Z", 600),
            session("2026-10-12T00:00:00Z", 300),
            session("2026-10-11T23:59:59Z", 900),
            session("2026-10-19T00:00:00Z", 1_200)
        )
        assertEquals(900, sessions.weeklyTotalSec(now, zone))
    }

    @Test
    fun `sin sesiones el total es cero`() {
        assertEquals(0, emptyList<PracticeSession>().weeklyTotalSec(now, zone))
    }

    /** Reloj que avanza con el tiempo virtual del test (el del dispositivo avanza con el real). */
    private class MovableClock(var at: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = at
    }

    @Test
    fun `nextWeekStart es el proximo lunes 00-00 de la zona, tambien en lunes y con DST`() {
        assertEquals(Instant.parse("2026-10-19T00:00:00Z"), now.nextWeekStart(zone))
        assertEquals(Instant.parse("2026-10-19T00:00:00Z"), Instant.parse("2026-10-12T00:00:00Z").nextWeekStart(zone))
        assertEquals(Instant.parse("2026-10-12T00:00:00Z"), Instant.parse("2026-10-11T23:59:59Z").nextWeekStart(zone))
        val madrid = ZoneId.of("Europe/Madrid")
        // Lunes 2026-10-26 00:00 en Madrid ya es CET (+1): termino el horario de verano el domingo 25.
        assertEquals(Instant.parse("2026-10-25T23:00:00Z"), Instant.parse("2026-10-21T10:00:00Z").nextWeekStart(madrid))
    }

    @Test
    fun `el flujo semanal recalcula al cruzar el lunes a las 00-00 sin nueva emision`() = runTest {
        val clock = MovableClock(Instant.parse("2026-10-18T23:59:00Z")) // domingo
        val sessions = listOf(session("2026-10-17T10:00:00Z", 600))
        val seen = mutableListOf<Int>()
        val job = launch { sessions.weeklyTotalFlow(clock) { ZoneOffset.UTC }.toList(seen) }
        runCurrent()
        assertEquals(listOf(600), seen)

        advanceTimeBy(59_000)
        runCurrent()
        assertEquals(listOf(600), seen)

        clock.at = Instant.parse("2026-10-19T00:00:00Z")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(600, 0), seen)
        job.cancel()
    }

    @Test
    fun `con un reloj que no avanza el flujo semanal emite una vez y termina`() = runTest {
        val clock = Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), ZoneOffset.UTC)
        val seen = listOf(session("2026-10-13T10:00:00Z", 600)).weeklyTotalFlow(clock) { ZoneOffset.UTC }.toList()
        assertEquals(listOf(600), seen)
    }
}
