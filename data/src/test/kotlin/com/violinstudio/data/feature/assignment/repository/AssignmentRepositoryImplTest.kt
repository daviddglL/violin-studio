package com.violinstudio.data.feature.assignment.repository

import app.cash.turbine.test
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreException.Code
import com.violinstudio.data.commons.firebase.RemoteDoc
import com.violinstudio.data.feature.assignment.datasource.FakeAssignmentRemoteDataSource
import com.violinstudio.domain.feature.assignment.failure.AssignmentFailure
import com.violinstudio.domain.feature.assignment.model.Assignment
import com.violinstudio.domain.feature.assignment.model.AssignmentDraft
import com.violinstudio.domain.feature.assignment.model.AssignmentStatus
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AssignmentRepositoryImplTest {
    private val remote = FakeAssignmentRemoteDataSource()
    private val repo = AssignmentRepositoryImpl(remote)
    private val due = Instant.parse("2026-04-01T10:00:00Z")
    private val created = Instant.parse("2026-03-01T10:00:00Z")

    private fun draft(description: String? = "d", dueAt: Instant? = due) =
        AssignmentDraft.create("id-1", " Escalas ", description, dueAt).getOrThrow()

    private fun data(vararg extra: Pair<String, Any?>) = mapOf<String, Any?>(
        "teacherUid" to "t1",
        "title" to "Escalas",
        "status" to "open",
        "createdAt" to Timestamp(created.epochSecond, 0),
        "updatedAt" to Timestamp(created.epochSecond, 0),
        "desconocido" to 1
    ) + extra

    @Test
    fun `observeForStudent mapea todos los campos y marca pendientes`() = runTest {
        val done = data("status" to "done", "doneAt" to Timestamp(due.epochSecond, 0))
        val full = data("description" to "d", "dueAt" to Timestamp(due.epochSecond, 0))
        repo.observeForStudent("s1").test {
            remote.snapshots.emit(listOf(RemoteDoc("a", full, true), RemoteDoc("b", done, false)))
            val list = awaitItem()
            assertEquals(
                Assignment("a", "t1", "Escalas", "d", due, AssignmentStatus.OPEN, null, created, true),
                list[0]
            )
            assertEquals(AssignmentStatus.DONE, list[1].status)
            assertEquals(due, list[1].doneAt)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("student:s1"), remote.observed)
    }

    @Test
    fun `escritura local sin serverTimestamp resuelto deja createdAt nulo y un doc ilegible se omite`() = runTest {
        val docs = listOf(
            RemoteDoc("a", data("createdAt" to null), true),
            RemoteDoc("b", data("status" to "raro"), false),
            RemoteDoc("c", data("title" to 5), false),
            RemoteDoc("d", data("teacherUid" to null), false),
            RemoteDoc("e", mapOf("x" to 1), false),
            RemoteDoc("f", data("dueAt" to "manana", "description" to 3), false)
        )
        repo.observeForStudent("s1").test {
            remote.snapshots.emit(docs)
            val list = awaitItem()
            assertEquals(listOf("a", "f"), list.map { it.id })
            assertEquals(null, list[0].createdAt)
            assertEquals(null, list[1].dueAt)
            assertEquals(null, list[1].description)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeByTeacher filtra por teacherUid y alumno`() = runTest {
        repo.observeByTeacher("t1", "s1").test { cancelAndIgnoreRemainingEvents() }
        assertEquals(listOf("teacher:t1:s1"), remote.observed)
    }

    @Test
    fun `el rechazo de las reglas retira el elemento pendiente del flujo`() = runTest {
        repo.observeByTeacher("t1", "s1").test {
            remote.snapshots.emit(listOf(RemoteDoc("a", data(), true)))
            assertEquals(true, awaitItem().single().pendingSync)
            remote.snapshots.emit(emptyList())
            assertEquals(emptyList<Assignment>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `permission-denied del listener es PermissionDenied y otro es Unknown`() = runTest {
        remote.observeFailure = FirebaseFirestoreException("x", Code.PERMISSION_DENIED)
        repo.observeForStudent("s1").test { assertSame(AssignmentFailure.PermissionDenied, awaitError()) }
        remote.observeFailure = IllegalStateException("boom")
        repo.observeByTeacher("t1", "s1").test { assertSame(AssignmentFailure.Unknown, awaitError()) }
    }

    @Test
    fun `create escribe solo los campos de las reglas con timestamps de servidor y sin nulos`() = runTest {
        assertTrue(repo.create("s1", "t1", draft()).isSuccess)
        val (student, id, fields) = remote.creates.single()
        assertEquals("s1", student)
        assertEquals("id-1", id)
        assertEquals(
            setOf("teacherUid", "title", "description", "dueAt", "status", "createdAt", "updatedAt"),
            fields.keys
        )
        assertEquals("Escalas", fields["title"])
        assertEquals("open", fields["status"])
        assertEquals(Timestamp(due.epochSecond, 0), fields["dueAt"])
        assertEquals(FieldValue.serverTimestamp(), fields["createdAt"])
        assertEquals(FieldValue.serverTimestamp(), fields["updatedAt"])
    }

    @Test
    fun `create omite descripcion y dueAt vacios`() = runTest {
        repo.create("s1", "t1", draft(description = null, dueAt = null))
        assertEquals(
            setOf("teacherUid", "title", "status", "createdAt", "updatedAt"),
            remote.creates.single().third.keys
        )
    }

    @Test
    fun `update borra con FieldValue delete los campos nulos y nunca envia null ni status`() = runTest {
        repo.update("s1", draft(description = null, dueAt = null))
        val fields = remote.updates.single().third
        assertEquals(setOf("title", "description", "dueAt", "updatedAt"), fields.keys)
        assertEquals(FieldValue.delete(), fields["description"])
        assertEquals(FieldValue.delete(), fields["dueAt"])
        assertEquals(FieldValue.serverTimestamp(), fields["updatedAt"])
        repo.update("s1", draft())
        assertEquals("d", remote.updates.last().third["description"])
        assertEquals(Timestamp(due.epochSecond, 0), remote.updates.last().third["dueAt"])
    }

    @Test
    fun `setDone completa con doneAt de servidor y reabrir lo borra`() = runTest {
        repo.setDone("s1", "id-1", true)
        assertEquals(mapOf("status" to "done", "doneAt" to FieldValue.serverTimestamp()), remote.updates[0].third)
        repo.setDone("s1", "id-1", false)
        assertEquals(mapOf("status" to "open", "doneAt" to FieldValue.delete()), remote.updates[1].third)
        assertEquals("id-1", remote.updates[1].second)
    }

    @Test
    fun `delete borra el doc y los fallos locales de escritura se mapean`() = runTest {
        assertTrue(repo.delete("s1", "id-1").isSuccess)
        assertEquals("s1" to "id-1", remote.deletes.single())
        remote.writeFailure = FirebaseFirestoreException("x", Code.PERMISSION_DENIED)
        assertSame(AssignmentFailure.PermissionDenied, repo.delete("s1", "id-1").exceptionOrNull())
        remote.writeFailure = IllegalStateException("boom")
        assertSame(AssignmentFailure.Unknown, repo.create("s1", "t1", draft()).exceptionOrNull())
    }
}
