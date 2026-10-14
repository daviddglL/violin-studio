package com.violinstudio.ui.feature.assignment.viewmodel

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AssignmentEditReducerTest {
    private fun reduce(state: AssignmentEditState, vararg m: AssignmentEditMutation) =
        m.fold(state) { s, mutation -> AssignmentEditReducer.reduce(s, mutation) }

    private val empty = AssignmentEditState()

    @Test
    fun `el formulario vacio no se puede guardar y no muestra errores hasta tocarlo`() {
        assertFalse(empty.canSave)
        assertNull(empty.titleError)
        assertNull(empty.descriptionError)
    }

    @Test
    fun `un titulo valido de 120 caracteres con descripcion de 1000 se puede guardar`() {
        val s = reduce(
            empty,
            AssignmentEditMutation.TitleEdited("a".repeat(120)),
            AssignmentEditMutation.DescriptionEdited("b".repeat(1000))
        )
        assertTrue(s.canSave)
        assertEquals(120, s.titleCount)
        assertEquals(1000, s.descriptionCount)
        assertNull(s.titleError)
        assertNull(s.descriptionError)
    }

    @Test
    fun `121 en el titulo y 1001 en la descripcion dan error inline al instante y bloquean guardar`() {
        val s = reduce(
            empty,
            AssignmentEditMutation.TitleEdited("a".repeat(121)),
            AssignmentEditMutation.DescriptionEdited("b".repeat(1001))
        )
        assertEquals(TitleError.TOO_LONG, s.titleError)
        assertEquals(DescriptionError.TOO_LONG, s.descriptionError)
        assertEquals(121, s.titleCount)
        assertFalse(s.canSave)
    }

    @Test
    fun `el contador y la validacion cuentan el texto recortado, igual que el dominio`() {
        val s = reduce(empty, AssignmentEditMutation.TitleEdited("  " + "a".repeat(120) + "  "))
        assertEquals(120, s.titleCount)
        assertTrue(s.canSave)
    }

    @Test
    fun `titulo vacio solo se marca tras escribir y borrar, o al intentar guardar`() {
        val typed = reduce(empty, AssignmentEditMutation.TitleEdited("x"), AssignmentEditMutation.TitleEdited(""))
        assertEquals(TitleError.EMPTY, typed.titleError)
        assertFalse(typed.canSave)
        val attempted = reduce(empty, AssignmentEditMutation.SaveAttempted)
        assertEquals(TitleError.EMPTY, attempted.titleError)
        assertFalse(attempted.canSave)
    }

    @Test
    fun `solo espacios es titulo vacio y un salto de linea es titulo invalido`() {
        assertEquals(TitleError.EMPTY, reduce(empty, AssignmentEditMutation.TitleEdited("   ")).titleError)
        val s = reduce(empty, AssignmentEditMutation.TitleEdited("uno\ndos"))
        assertEquals(TitleError.INVALID, s.titleError)
        assertFalse(s.canSave)
    }

    @Test
    fun `la fecha limite es opcional y se puede quitar`() {
        val due = Instant.parse("2026-12-01T00:00:00Z")
        val withDue = reduce(empty, AssignmentEditMutation.TitleEdited("Escalas"), AssignmentEditMutation.DueAtChanged(due))
        assertEquals(due, withDue.dueAt)
        assertTrue(withDue.canSave)
        val cleared = reduce(withDue, AssignmentEditMutation.DueAtChanged(null))
        assertNull(cleared.dueAt)
        assertTrue(cleared.canSave)
    }

    @Test
    fun `Loaded rellena el formulario para editar y limpia el estado de carga`() {
        val due = Instant.parse("2026-12-01T00:00:00Z")
        val s = reduce(empty, AssignmentEditMutation.Loaded("Escalas", "Do mayor", due))
        assertEquals("Escalas", s.title)
        assertEquals("Do mayor", s.description)
        assertEquals(due, s.dueAt)
        assertTrue(s.isEditing)
        assertTrue(s.canSave)
    }

    @Test
    fun `Loaded sin descripcion deja el campo vacio`() {
        val s = reduce(empty, AssignmentEditMutation.Loaded("Escalas", null, null))
        assertEquals("", s.description)
    }

    @Test
    fun `mientras guarda no se puede volver a guardar y un fallo lo libera con el error`() {
        val ready = reduce(empty, AssignmentEditMutation.TitleEdited("Escalas"))
        val busy = reduce(ready, AssignmentEditMutation.Busy(true))
        assertFalse(busy.canSave)
        val failed = reduce(busy, AssignmentEditMutation.Failed(AssignmentEditError.PERMISSION_DENIED))
        assertFalse(failed.busy)
        assertEquals(AssignmentEditError.PERMISSION_DENIED, failed.error)
        assertTrue(failed.canSave)
    }

    @Test
    fun `editar un campo limpia el error de guardado`() {
        val failed = reduce(
            empty,
            AssignmentEditMutation.TitleEdited("Escalas"),
            AssignmentEditMutation.Failed(AssignmentEditError.UNKNOWN)
        )
        assertEquals(null, reduce(failed, AssignmentEditMutation.TitleEdited("Escalas 2")).error)
        assertEquals(null, reduce(failed, AssignmentEditMutation.ErrorDismissed).error)
    }

    @Test
    fun `mientras carga la tarea a editar o si no existe no se puede guardar`() {
        val loading = AssignmentEditState(loading = true)
        val withTitle = reduce(loading, AssignmentEditMutation.TitleEdited("Escalas"))
        assertFalse(withTitle.canSave)
        val loaded = reduce(loading, AssignmentEditMutation.Loaded("Escalas", null, null))
        assertFalse(loaded.loading)
        assertTrue(loaded.canSave)
        val missing = reduce(loading, AssignmentEditMutation.NotFound)
        assertFalse(missing.loading)
        assertTrue(missing.notFound)
        assertFalse(reduce(missing, AssignmentEditMutation.TitleEdited("Escalas")).canSave)
    }

    @Test
    fun `toString no incluye titulo ni descripcion`() {
        val s = reduce(empty, AssignmentEditMutation.TitleEdited("secreto-titulo"), AssignmentEditMutation.DescriptionEdited("secreto-desc"))
        assertFalse(s.toString().contains("secreto"))
    }
}
