package com.violinstudio.data.feature.practice.utils

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.violinstudio.data.feature.practice.utils.extensions.notesUpdate
import com.violinstudio.data.feature.practice.utils.extensions.toDomain
import com.violinstudio.data.feature.practice.utils.extensions.toFields
import com.violinstudio.domain.feature.practice.model.PracticeDraft
import com.violinstudio.domain.feature.profile.model.Instrument
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PracticeSessionMapperTest {
    private val start = Instant.parse("2026-03-01T10:00:00Z")
    private val now = Instant.parse("2026-03-01T11:00:00Z")

    private fun draft(notes: String? = null, instrument: Instrument = Instrument.DOUBLE_BASS) =
        PracticeDraft.create("id-1", start, 90, instrument, notes, now).getOrThrow()

    private val ok = mapOf("startedAt" to Timestamp(1, 0), "durationSec" to 5, "instrument" to "violin")

    @Test
    fun `sin notas el conjunto exacto de claves es startedAt durationSec instrument createdAt`() {
        val fields = draft().toFields()
        assertEquals(setOf("startedAt", "durationSec", "instrument", "createdAt"), fields.keys)
        assertEquals(90, fields["durationSec"])
        assertEquals("double_bass", fields["instrument"])
        assertEquals(Timestamp(start.epochSecond, 0), fields["startedAt"])
        assertEquals(FieldValue.serverTimestamp(), fields["createdAt"])
    }

    @Test
    fun `con notas se escriben y vacias no hay campo`() {
        assertEquals("bien", draft("bien").toFields()["notes"])
        assertFalse(draft("   ").toFields().containsKey("notes"))
    }

    @Test
    fun `notesUpdate escribe la nota o borra el campo`() {
        assertEquals(mapOf("notes" to "hola"), notesUpdate("  hola "))
        assertEquals(mapOf("notes" to FieldValue.delete()), notesUpdate(null))
        assertEquals(mapOf("notes" to FieldValue.delete()), notesUpdate("   "))
    }

    @Test
    fun `ida y vuelta devuelve la sesion original`() {
        val data = mapOf(
            "startedAt" to Timestamp(start.epochSecond, 0),
            "durationSec" to 90L,
            "instrument" to "double_bass",
            "notes" to "bien"
        )
        val session = PracticeSessionParser.parse("id-1", data, pending = true).toDomain()!!
        assertEquals("id-1", session.id)
        assertEquals(start, session.startedAt)
        assertEquals(90, session.durationSec)
        assertEquals(Instrument.DOUBLE_BASS, session.instrument)
        assertEquals("bien", session.notes)
        assertEquals(true, session.pendingSync)
    }

    @Test
    fun `sin notas el campo ausente da null y una nota de otro tipo se ignora`() {
        assertNull(PracticeSessionParser.parse("a", ok, false).toDomain()!!.notes)
        assertNull(PracticeSessionParser.parse("a", ok + ("notes" to 3), false).toDomain()!!.notes)
    }

    @Test
    fun `docs ilegibles se omiten`() {
        listOf(
            ok - "durationSec",
            ok - "startedAt",
            ok + ("instrument" to "theremin"),
            ok + ("durationSec" to "5"),
            ok + ("durationSec" to 0),
            ok + ("durationSec" to 5.5),
            ok + ("startedAt" to "ayer")
        ).forEach { assertNull(PracticeSessionParser.parse("a", it, false).toDomain(), it.toString()) }
    }

    @Test
    fun `startedAt conserva los nanosegundos`() {
        val precise = PracticeDraft.create("id-1", start.plusNanos(123_000_000), 90, Instrument.VIOLIN, null, now)
            .getOrThrow()
        assertEquals(Timestamp(start.epochSecond, 123_000_000), precise.toFields()["startedAt"])
    }
}
