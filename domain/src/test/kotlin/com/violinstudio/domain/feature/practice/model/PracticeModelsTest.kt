package com.violinstudio.domain.feature.practice.model

import com.violinstudio.domain.feature.practice.failure.PracticeFailure
import com.violinstudio.domain.feature.profile.model.Instrument
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PracticeModelsTest {
    private val now = Instant.parse("2026-03-10T10:00:00Z")
    private val start = now.minusSeconds(600)

    private fun draft(duration: Int = 60, notes: String? = null, startedAt: Instant = start) =
        PracticeDraft.create("id-1", startedAt, duration, Instrument.VIOLIN, notes, now)

    @Test
    fun `durationSec valido de 1 a 43200`() {
        assertEquals(1, draft(1).getOrThrow().durationSec)
        assertEquals(43_200, draft(43_200).getOrThrow().durationSec)
    }

    @Test
    fun `durationSec fuera de rango se rechaza`() {
        listOf(0, -1, 43_201).forEach {
            assertEquals(PracticeFailure.InvalidDuration, draft(it).exceptionOrNull())
        }
    }

    @Test
    fun `las notas se recortan, vacias pasan a null y 500 unidades UTF-16 es el maximo`() {
        assertEquals("hola", draft(notes = "  hola \n").getOrThrow().notes)
        assertNull(draft(notes = "   ").getOrThrow().notes)
        assertNull(draft(notes = null).getOrThrow().notes)
        assertEquals(500, draft(notes = "a".repeat(500)).getOrThrow().notes?.length)
        assertEquals(PracticeFailure.NotesTooLong, draft(notes = "a".repeat(501)).exceptionOrNull())
        // un emoji fuera del BMP son 2 unidades: 251 emojis = 502
        assertEquals(PracticeFailure.NotesTooLong, draft(notes = "🎻".repeat(251)).exceptionOrNull())
    }

    @Test
    fun `startedAt futuro se rechaza y el id debe ser acotado`() {
        assertEquals(PracticeFailure.InvalidStart, draft(startedAt = now.plusSeconds(1)).exceptionOrNull())
        assertEquals(
            PracticeFailure.InvalidId,
            PracticeDraft.create("", start, 60, Instrument.VIOLIN, null, now).exceptionOrNull()
        )
        assertEquals(
            PracticeFailure.InvalidId,
            PracticeDraft.create("x".repeat(65), start, 60, Instrument.VIOLIN, null, now).exceptionOrNull()
        )
    }
}
