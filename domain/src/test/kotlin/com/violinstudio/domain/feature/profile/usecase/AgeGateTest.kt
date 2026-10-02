package com.violinstudio.domain.feature.profile.usecase

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgeGateTest {
    private val clock = Clock.fixed(Instant.parse("2026-05-20T12:00:00Z"), ZoneOffset.UTC)
    private val gate = AgeGate(threshold = 14, clock = clock)

    @Test
    fun `cumple 14 hoy no es menor`() {
        assertFalse(gate.isBelowThreshold(LocalDate.of(2012, 5, 20)))
    }

    @Test
    fun `cumple 14 manana sigue siendo menor`() {
        assertTrue(gate.isBelowThreshold(LocalDate.of(2012, 5, 21)))
    }

    @Test
    fun `calcula la edad en anos completos`() {
        assertEquals(14, gate.ageOf(LocalDate.of(2012, 5, 20)))
        assertEquals(13, gate.ageOf(LocalDate.of(2012, 5, 21)))
        assertEquals(25, gate.ageOf(LocalDate.of(2000, 5, 21)))
    }

    @Test
    fun `una fecha futura no es adulta`() {
        assertTrue(gate.isBelowThreshold(LocalDate.of(2030, 1, 1)))
    }

    private fun gateAt(instant: String, zone: String) =
        AgeGate(14, Clock.fixed(Instant.parse(instant), ZoneId.of(zone)))

    @Test
    fun `usa la fecha UTC aunque el reloj tenga zona (como el servidor)`() {
        // 2026-05-20T23:30Z es ya el 21 en UTC+13, pero sigue siendo el 20 en UTC.
        val ahead = gateAt("2026-05-20T23:30:00Z", "Pacific/Auckland")
        assertFalse(ahead.isBelowThreshold(LocalDate.of(2012, 5, 20)))
        assertTrue(ahead.isBelowThreshold(LocalDate.of(2012, 5, 21)))
        // 2026-05-21T02:00Z es aun el 20 en UTC-8, pero ya el 21 en UTC.
        val behind = gateAt("2026-05-21T02:00:00Z", "America/Los_Angeles")
        assertFalse(behind.isBelowThreshold(LocalDate.of(2012, 5, 21)))
    }

    @Test
    fun `quien nace un 29 de febrero cumple el 1 de marzo en anos no bisiestos`() {
        val birth = LocalDate.of(2012, 2, 29)
        assertTrue(gateAt("2026-02-28T12:00:00Z", "UTC").isBelowThreshold(birth))
        assertFalse(gateAt("2026-03-01T00:00:00Z", "UTC").isBelowThreshold(birth))
    }
}
