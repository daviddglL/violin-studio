package com.violinstudio.ui.feature.assignment.view

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DueDateFormatTest {
    private val due = Instant.parse("2026-10-20T12:00:00Z")
    private val es = Locale.forLanguageTag("es-ES")
    private val en = Locale.forLanguageTag("en-US")

    @Test
    fun `la fecha limite se formatea segun el locale`() {
        val spanish = formatDueDate(due, es, ZoneOffset.UTC)
        val english = formatDueDate(due, en, ZoneOffset.UTC)
        assertTrue(spanish.contains("oct"), spanish)
        assertTrue(english.startsWith("Oct 20"), english)
        assertTrue(spanish != english)
    }

    @Test
    fun `la fecha usa la zona del dispositivo`() {
        val late = Instant.parse("2026-10-20T23:30:00Z")
        assertTrue(formatDueDate(late, en, ZoneOffset.ofHours(2)).startsWith("Oct 21"))
        assertTrue(formatDueDate(late, en, ZoneOffset.UTC).startsWith("Oct 20"))
    }

    @Test
    fun `el dia elegido se convierte al inicio de ese dia en la zona local y vuelve igual`() {
        val zone = ZoneId.of("Europe/Madrid")
        val instant = dueAtOfLocalDate(LocalDate.of(2026, 10, 20), zone)
        assertEquals(Instant.parse("2026-10-19T22:00:00Z"), instant)
        assertEquals(LocalDate.of(2026, 10, 20), localDateOf(instant, zone))
    }
}
