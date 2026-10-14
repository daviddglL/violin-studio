package com.violinstudio.domain.feature.practice.usecase

import com.violinstudio.domain.feature.practice.model.PracticeSession
import com.violinstudio.domain.feature.profile.model.Instrument
import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

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
}
