package com.violinstudio.ui.feature.practice.viewmodel

import com.violinstudio.domain.feature.practice.failure.PracticeFailure
import com.violinstudio.domain.feature.practice.model.RunningSession
import com.violinstudio.domain.feature.profile.model.Instrument
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PracticeReducerTest {
    private val t0 = Instant.parse("2026-01-05T10:00:00Z")
    private val running = RunningSession("r1", t0, Instrument.VIOLIN)
    private val open = PracticeReducer.reduce(PracticeState(), PracticeMutation.SessionOpened(Instrument.VIOLA))

    private fun reduce(state: PracticeState, vararg m: PracticeMutation) =
        m.fold(state) { s, mutation -> PracticeReducer.reduce(s, mutation) }

    @Test
    fun `el cronometro sale de startedAt y del reloj, no de un contador`() {
        val s = reduce(open, PracticeMutation.RunningChanged(running, t0.plusSeconds(120)))
        assertEquals(120, s.elapsedSec)
        assertEquals(185, reduce(s, PracticeMutation.Tick(t0.plusSeconds(185))).elapsedSec)
        assertEquals(0, reduce(s, PracticeMutation.Tick(t0.minusSeconds(5))).elapsedSec)
    }

    @Test
    fun `sin sesion en curso se cierra el dialogo y se limpian notas y cronometro`() {
        val s = reduce(
            open,
            PracticeMutation.RunningChanged(running, t0),
            PracticeMutation.SaveOpened,
            PracticeMutation.NotesEdited("hola"),
            PracticeMutation.RunningChanged(null, t0)
        )
        assertFalse(s.showSave)
        assertEquals("", s.draftNotes)
        assertEquals(0, s.elapsedSec)
    }

    @Test
    fun `mas de 500 caracteres de notas bloquean guardar`() {
        val ok = reduce(open, PracticeMutation.NotesEdited("a".repeat(500)))
        val long = reduce(open, PracticeMutation.NotesEdited("a".repeat(501)))
        assertEquals(500, ok.notesCount)
        assertFalse(ok.notesTooLong)
        assertTrue(long.notesTooLong)
    }

    @Test
    fun `TooShort descarta y avisa, y un recorte a 12 h avisa`() {
        val saving = reduce(open, PracticeMutation.SaveOpened)
        val short = reduce(saving, PracticeMutation.Failed(PracticeFailure.TooShort))
        assertFalse(short.showSave)
        assertEquals(PracticeMessage.TOO_SHORT, short.message)
        assertEquals(PracticeMessage.CLAMPED, reduce(saving, PracticeMutation.Stopped(clamped = true)).message)
        assertNull(reduce(saving, PracticeMutation.Stopped(clamped = false)).message)
        assertNull(reduce(saving, PracticeMutation.Stopped(clamped = false)).running)
    }

    @Test
    fun `un fallo del historial lo vacia y distingue permiso de error desconocido`() {
        val loaded = reduce(open, PracticeMutation.WeeklyLoaded(600))
        val denied = reduce(loaded, PracticeMutation.StreamFailed(PracticeFailure.PermissionDenied, clearData = true))
        assertEquals(0, denied.weeklyTotalSec)
        assertEquals(PracticeMessage.PERMISSION_DENIED, denied.message)
        assertEquals(PracticeMessage.UNKNOWN, reduce(open, PracticeMutation.StreamFailed(Exception(), false)).message)
    }

    @Test
    fun `parar sin recorte conserva el mensaje previo y un fallo de flujo no pisa un aviso`() {
        val denied = reduce(open, PracticeMutation.Failed(PracticeFailure.PermissionDenied))
        assertEquals(PracticeMessage.PERMISSION_DENIED, reduce(denied, PracticeMutation.Stopped(false)).message)
        val clamped = reduce(open, PracticeMutation.Stopped(clamped = true))
        val failed = reduce(clamped, PracticeMutation.StreamFailed(PracticeFailure.PermissionDenied, true))
        assertEquals(PracticeMessage.CLAMPED, failed.message)
        assertEquals(0, failed.weeklyTotalSec)
    }

    @Test
    fun `el instrumento del perfil solo se aplica mientras el usuario no haya elegido`() {
        assertEquals(
            Instrument.CELLO,
            reduce(open, PracticeMutation.ProfileInstrumentChanged(Instrument.CELLO)).instrument
        )
        val chosen = reduce(open, PracticeMutation.InstrumentSelected(Instrument.VIOLIN))
        val after = reduce(chosen, PracticeMutation.ProfileInstrumentChanged(Instrument.CELLO))
        assertEquals(Instrument.VIOLIN, after.instrument)
    }

    @Test
    fun `cerrar la sesion deja el estado vacio y el instrumento elegido manda sobre el del perfil`() {
        val chosen = reduce(open, PracticeMutation.InstrumentSelected(Instrument.CELLO))
        assertEquals(Instrument.CELLO, chosen.instrument)
        assertEquals(PracticeState(), reduce(chosen, PracticeMutation.SessionClosed))
    }
}
