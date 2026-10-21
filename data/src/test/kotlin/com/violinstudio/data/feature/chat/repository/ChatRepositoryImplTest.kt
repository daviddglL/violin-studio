package com.violinstudio.data.feature.chat.repository

import app.cash.turbine.test
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreException.Code
import com.violinstudio.data.commons.firebase.RemoteDoc
import com.violinstudio.data.feature.chat.datasource.FakeChatRemoteDataSource
import com.violinstudio.domain.feature.chat.failure.ChatFailure
import com.violinstudio.domain.feature.chat.model.ChatMessage
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatRepositoryImplTest {
    private val remote = FakeChatRemoteDataSource()
    private val repo = ChatRepositoryImpl(remote)
    private val created = Instant.parse("2026-03-01T10:00:00Z")
    private val expire = Instant.parse("2026-08-28T10:00:00.5Z")

    private fun data(vararg extra: Pair<String, Any?>) = mapOf<String, Any?>(
        "teacherUid" to "t1",
        "studentUid" to "s1",
        "senderUid" to "s1",
        "text" to "Hola",
        "createdAt" to Timestamp(created.epochSecond, 0),
        "expireAt" to Timestamp(expire.epochSecond, 0)
    ) + extra

    @Test
    fun `observe filtra por el par y mapea los mensajes`() = runTest {
        repo.observe("t1", "s1").test {
            remote.snapshots.emit(
                listOf(RemoteDoc("m1", data(), false), RemoteDoc("m2", data("createdAt" to null), true))
            )
            assertEquals(
                listOf(ChatMessage("m1", "s1", "Hola", created, false), ChatMessage("m2", "s1", "Hola", null, true)),
                awaitItem()
            )
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("t1:s1"), remote.observed)
    }

    @Test
    fun `un doc sin autor o sin texto se omite`() = runTest {
        repo.observe("t1", "s1").test {
            remote.snapshots.emit(
                listOf(
                    RemoteDoc("a", data("senderUid" to null), false),
                    RemoteDoc("b", data("text" to 3), false),
                    RemoteDoc("c", data("createdAt" to "ayer"), false)
                )
            )
            val list = awaitItem()
            assertEquals(listOf("c"), list.map { it.id })
            assertEquals(null, list.single().createdAt)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `permission-denied del listener es PermissionDenied y otro es Unknown`() = runTest {
        remote.observeFailure = FirebaseFirestoreException("x", Code.PERMISSION_DENIED)
        repo.observe("t1", "s1").test { assertSame(ChatFailure.PermissionDenied, awaitError()) }
        remote.observeFailure = IllegalStateException("boom")
        repo.observe("t1", "s1").test { assertSame(ChatFailure.Unknown, awaitError()) }
    }

    @Test
    fun `send escribe solo los campos de las reglas con createdAt del servidor`() = runTest {
        assertTrue(repo.send("t1", "s1", "s1", "m1", "Hola", expire).isSuccess)
        val (id, fields) = remote.creates.single()
        assertEquals("m1", id)
        assertEquals(
            mapOf(
                "teacherUid" to "t1",
                "studentUid" to "s1",
                "senderUid" to "s1",
                "text" to "Hola",
                "createdAt" to FieldValue.serverTimestamp(),
                "expireAt" to Timestamp(expire.epochSecond, 500_000_000)
            ),
            fields
        )
    }

    @Test
    fun `delete borra por id y los fallos locales se mapean`() = runTest {
        assertTrue(repo.delete("m1").isSuccess)
        assertEquals(listOf("m1"), remote.deletes)
        remote.writeFailure = FirebaseFirestoreException("x", Code.PERMISSION_DENIED)
        assertEquals(ChatFailure.PermissionDenied, repo.delete("m2").exceptionOrNull())
        remote.writeFailure = IllegalStateException("boom")
        assertEquals(ChatFailure.Unknown, repo.send("t1", "s1", "s1", "m3", "x", expire).exceptionOrNull())
    }
}
