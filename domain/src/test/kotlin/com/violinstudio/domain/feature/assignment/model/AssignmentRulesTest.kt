package com.violinstudio.domain.feature.assignment.model

import com.violinstudio.domain.feature.assignment.failure.AssignmentFailure
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AssignmentRulesTest {
    private fun at(s: Long) = Instant.ofEpochSecond(s)

    private fun task(
        id: String,
        status: AssignmentStatus = AssignmentStatus.OPEN,
        dueAt: Instant? = null,
        doneAt: Instant? = null,
        createdAt: Instant? = at(1)
    ) = Assignment(id, "t", "titulo", null, dueAt, status, doneAt, createdAt, false)

    @Test
    fun `titulo de 120 es valido y de 121 o vacio se rechaza`() {
        assertEquals("a".repeat(120), AssignmentRules.title("a".repeat(120)).getOrThrow())
        assertEquals(AssignmentFailure.TitleTooLong, AssignmentRules.title("a".repeat(121)).exceptionOrNull())
        assertEquals(AssignmentFailure.TitleEmpty, AssignmentRules.title("").exceptionOrNull())
        assertEquals(AssignmentFailure.TitleEmpty, AssignmentRules.title("   ").exceptionOrNull())
    }

    @Test
    fun `el titulo se recorta y la longitud cuenta unidades UTF-16 como las reglas`() {
        assertEquals("Escala", AssignmentRules.title("  Escala ").getOrThrow())
        // 61 emojis = 122 unidades UTF-16 (pero 61 puntos de codigo): las reglas lo rechazan.
        assertEquals(AssignmentFailure.TitleTooLong, AssignmentRules.title("🎻".repeat(61)).exceptionOrNull())
        assertEquals(120, AssignmentRules.title("🎻".repeat(60)).getOrThrow().length)
    }

    @Test
    fun `el titulo rechaza saltos de linea y caracteres de control o formato`() {
        for (bad in listOf("a\nb", "a\r\nb", "a\tb", "a\u0000b", "a​b", "a‮b")) {
            assertEquals(AssignmentFailure.TitleInvalid, AssignmentRules.title(bad).exceptionOrNull(), bad)
        }
        assertEquals("Mi bemol: Do, Re (30'); ñあ", AssignmentRules.title("Mi bemol: Do, Re (30'); ñあ").getOrThrow())
    }

    @Test
    fun `descripcion de 1000 es valida, vacia es null y 1001 se rechaza`() {
        assertEquals("a".repeat(1000), AssignmentRules.description("a".repeat(1000)).getOrThrow())
        assertNull(AssignmentRules.description(null).getOrThrow())
        assertNull(AssignmentRules.description("  \n ").getOrThrow())
        val tooLong = AssignmentRules.description("a".repeat(1001)).exceptionOrNull()
        assertEquals(AssignmentFailure.DescriptionTooLong, tooLong)
    }

    @Test
    fun `el borrador valida id, titulo y descripcion y dueAt es opcional`() {
        val draft = AssignmentDraft.create("id1", " Escala ", " ", null).getOrThrow()
        assertEquals(AssignmentDraft.create("id1", "Escala", null, null).getOrThrow(), draft)
        assertEquals(AssignmentFailure.InvalidId, AssignmentDraft.create("", "x", null, null).exceptionOrNull())
        val longId = AssignmentDraft.create("i".repeat(65), "x", null, null)
        assertEquals(AssignmentFailure.InvalidId, longId.exceptionOrNull())
        assertEquals(at(5), AssignmentDraft.create("i".repeat(64), "x", "d", at(5)).getOrThrow().dueAt)
        assertEquals(AssignmentFailure.TitleEmpty, AssignmentDraft.create("id", "", null, null).exceptionOrNull())
        val longDesc = AssignmentDraft.create("id", "x", "d".repeat(1001), null)
        assertEquals(AssignmentFailure.DescriptionTooLong, longDesc.exceptionOrNull())
    }

    @Test
    fun `orden del alumno - abiertas primero por dueAt, sin dueAt al final de las abiertas, hechas despues`() {
        val sorted = AssignmentRules.sortForStudent(
            listOf(
                task("done-old", AssignmentStatus.DONE, doneAt = at(10)),
                task("open-none-new", createdAt = at(9)),
                task("open-late", dueAt = at(300)),
                task("done-new", AssignmentStatus.DONE, doneAt = at(20)),
                task("open-none-old", createdAt = at(2)),
                task("open-soon", dueAt = at(100))
            )
        )
        assertEquals(
            listOf("open-soon", "open-late", "open-none-old", "open-none-new", "done-new", "done-old"),
            sorted.map { it.id }
        )
    }

    @Test
    fun `una escritura pendiente sin createdAt va despues de las demas sin dueAt`() {
        val list = listOf(task("pending", createdAt = null), task("old", createdAt = at(1)))
        val sorted = AssignmentRules.sortForStudent(list)
        assertEquals(listOf("old", "pending"), sorted.map { it.id })
    }
}
