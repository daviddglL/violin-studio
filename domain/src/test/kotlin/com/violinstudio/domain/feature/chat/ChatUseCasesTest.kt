package com.violinstudio.domain.feature.chat

import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.FakeConsentRepository
import com.violinstudio.domain.feature.chat.failure.ChatFailure
import com.violinstudio.domain.feature.chat.model.ChatMessage
import com.violinstudio.domain.feature.chat.repository.ChatRepository
import com.violinstudio.domain.feature.chat.usecase.DeleteChatMessageUseCase
import com.violinstudio.domain.feature.chat.usecase.IsChatEnabledUseCase
import com.violinstudio.domain.feature.chat.usecase.ObserveChatUseCase
import com.violinstudio.domain.feature.chat.usecase.SendChatMessageUseCase
import com.violinstudio.domain.feature.config
import com.violinstudio.domain.feature.consent.failure.ConsentFailure
import com.violinstudio.domain.feature.verifiedUser
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatUseCasesTest {
    private val now = Instant.parse("2026-10-21T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val auth = FakeAuthRepository()

    private class FakeChat : ChatRepository {
        var messages: List<ChatMessage> = emptyList()
        val calls = mutableListOf<String>()
        var result: Result<Unit> = Result.success(Unit)

        override fun observe(teacherUid: String, studentUid: String): Flow<List<ChatMessage>> {
            calls += "observe:$teacherUid/$studentUid"
            return flowOf(messages)
        }

        override suspend fun send(
            teacherUid: String,
            studentUid: String,
            senderUid: String,
            id: String,
            text: String,
            expireAt: Instant
        ): Result<Unit> {
            calls += "send:$teacherUid/$studentUid/$senderUid/$id/$text/$expireAt"
            return result
        }

        override suspend fun delete(id: String): Result<Unit> {
            calls += "delete:$id"
            return result
        }
    }

    private val repo = FakeChat()
    private val send = SendChatMessageUseCase(auth, repo, clock) { "m1" }

    private fun message(id: String, at: Instant?) = ChatMessage(id, "u1", "hola", at, pendingSync = at == null)

    @Test
    fun `la conversacion sale en orden de llegada con los pendientes al final y con el uid de la sesion`() = runTest {
        auth.user = verifiedUser
        repo.messages = listOf(
            message("p1", null),
            message("b", now.plusSeconds(5)),
            message("a", now),
            message("p2", null)
        )
        val thread = ObserveChatUseCase(auth, repo)("t1", "s1").first()
        assertEquals("u1", thread.myUid)
        assertEquals(listOf("a", "b", "p1", "p2"), thread.messages.map { it.id })
        assertEquals(listOf("observe:t1/s1"), repo.calls)
    }

    @Test
    fun `sin sesion no se observa ni se envia`() = runTest {
        assertNull(withTimeoutOrNull(1_000) { ObserveChatUseCase(auth, repo)("t1", "s1").first() })
        assertEquals(ChatFailure.NoSession, send("t1", "s1", "hola").exceptionOrNull())
        assertTrue(repo.calls.isEmpty())
    }

    @Test
    fun `enviar recorta el texto, firma como el usuario y caduca a los 180 dias`() = runTest {
        auth.user = verifiedUser
        assertTrue(send("t1", "s1", "  hola  ").isSuccess)
        assertEquals(listOf("send:t1/s1/u1/m1/hola/2027-04-19T10:00:00Z"), repo.calls)
    }

    @Test
    fun `vacio o demasiado largo no sale del dispositivo`() = runTest {
        auth.user = verifiedUser
        assertEquals(ChatFailure.Empty, send("t1", "s1", "   ").exceptionOrNull())
        assertEquals(ChatFailure.TooLong, send("t1", "s1", "a".repeat(1_001)).exceptionOrNull())
        assertTrue(send("t1", "s1", "a".repeat(1_000)).isSuccess)
        assertEquals(1, repo.calls.size)
    }

    @Test
    fun `un fallo del repositorio llega tal cual y borrar delega`() = runTest {
        auth.user = verifiedUser
        repo.result = Result.failure(ChatFailure.PermissionDenied)
        assertEquals(ChatFailure.PermissionDenied, send("t1", "s1", "hola").exceptionOrNull())
        assertEquals(ChatFailure.PermissionDenied, DeleteChatMessageUseCase(repo)("m9").exceptionOrNull())
        assertEquals("delete:m9", repo.calls.last())
    }

    @Test
    fun `los volcados no llevan texto ni uids`() {
        val m = ChatMessage("id1", "uidX", "secreto", null, pendingSync = true)
        assertTrue("secreto" !in m.toString() && "uidX" !in m.toString())
    }

    @Test
    fun `el interruptor sale de la config del servidor y un fallo de lectura se propaga`() = runTest {
        val consent = FakeConsentRepository()
        consent.configResult = Result.success(config.copy(chatEnabled = true))
        assertEquals(true, IsChatEnabledUseCase(consent)().getOrThrow())
        consent.configResult = Result.success(config)
        assertEquals(false, IsChatEnabledUseCase(consent)().getOrThrow())
        consent.configResult = Result.failure(ConsentFailure.Unknown())
        assertTrue(IsChatEnabledUseCase(consent)().isFailure)
    }
}
