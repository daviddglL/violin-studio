package com.violinstudio.data.feature.practice.repository

import app.cash.turbine.test
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreException.Code
import com.violinstudio.data.feature.practice.datasource.FakePracticeRemoteDataSource
import com.violinstudio.data.feature.practice.datasource.PracticeDoc
import com.violinstudio.domain.feature.practice.failure.PracticeFailure
import com.violinstudio.domain.feature.practice.model.PracticeDraft
import com.violinstudio.domain.feature.profile.model.Instrument
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FirestorePracticeLogRepositoryTest {
    private val remote = FakePracticeRemoteDataSource()
    private val repo = FirestorePracticeLogRepository(remote)
    private val start = Instant.parse("2026-03-01T10:00:00Z")
    private val draft =
        PracticeDraft.create("id-1", start, 60, Instrument.CELLO, null, start.plusSeconds(100)).getOrThrow()

    private fun doc(id: String, pending: Boolean = false) = PracticeDoc(
        id,
        mapOf("startedAt" to Timestamp(start.epochSecond, 0), "durationSec" to 60, "instrument" to "cello"),
        pending
    )

    @Test
    fun `observeHistory mapea, omite docs ilegibles y marca pendientes`() = runTest {
        repo.observeHistory("u1", 50).test {
            remote.snapshots.emit(listOf(doc("a", pending = true), PracticeDoc("b", mapOf("x" to 1), false), doc("c")))
            val list = awaitItem()
            assertEquals(listOf("a", "c"), list.map { it.id })
            assertEquals(listOf(true, false), list.map { it.pendingSync })
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("u1" to 50, remote.observed.single())
    }

    @Test
    fun `permission-denied del listener es PermissionDenied y otro es Unknown`() = runTest {
        remote.observeFailure = FirebaseFirestoreException("x", Code.PERMISSION_DENIED)
        repo.observeHistory("u1").test { assertSame(PracticeFailure.PermissionDenied, awaitError()) }
        remote.observeFailure = IllegalStateException("boom")
        repo.observeHistory("u1").test { assertSame(PracticeFailure.Unknown, awaitError()) }
    }

    @Test
    fun `create escribe el doc con el id del borrador`() = runTest {
        assertTrue(repo.create("u1", draft).isSuccess)
        val (uid, id, fields) = remote.creates.single()
        assertEquals("u1", uid)
        assertEquals("id-1", id)
        assertEquals(setOf("startedAt", "durationSec", "instrument", "createdAt"), fields.keys)
    }

    @Test
    fun `fallos de escritura locales se mapean`() = runTest {
        remote.writeFailure = FirebaseFirestoreException("x", Code.PERMISSION_DENIED)
        assertSame(PracticeFailure.PermissionDenied, repo.create("u1", draft).exceptionOrNull())
        assertSame(PracticeFailure.PermissionDenied, repo.delete("u1", "id-1").exceptionOrNull())
        remote.writeFailure = IllegalStateException("x")
        assertSame(PracticeFailure.Unknown, repo.updateNotes("u1", "id-1", "n").exceptionOrNull())
    }

    @Test
    fun `updateNotes envia solo la clave notes y null la borra`() = runTest {
        assertTrue(repo.updateNotes("u1", "id-1", "hola").isSuccess)
        assertTrue(repo.updateNotes("u1", "id-1", null).isSuccess)
        assertEquals(mapOf("notes" to "hola"), remote.updates[0].third)
        assertEquals(mapOf("notes" to FieldValue.delete()), remote.updates[1].third)
    }

    @Test
    fun `delete y exists delegan`() = runTest {
        assertTrue(repo.delete("u1", "id-1").isSuccess)
        assertEquals("u1" to "id-1", remote.deletes.single())
        remote.existing = setOf("id-1")
        assertTrue(repo.exists("u1", "id-1"))
        assertFalse(repo.exists("u1", "id-2"))
    }
}
