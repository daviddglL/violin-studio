package com.violinstudio.domain.feature.profile.usecase

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
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
}
