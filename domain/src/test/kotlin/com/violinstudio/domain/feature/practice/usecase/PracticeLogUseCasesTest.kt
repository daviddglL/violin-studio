package com.violinstudio.domain.feature.practice.usecase

import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.practice.failure.PracticeFailure
import com.violinstudio.domain.feature.practice.model.PracticeSession
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.verifiedUser
import com.violinstudio.domain.testing.FakePracticeLogRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PracticeLogUseCasesTest {
    private val auth = FakeAuthRepository().apply { user = verifiedUser }
    private val repo = FakePracticeLogRepository()

    private fun session(id: String, at: String, seconds: Int) =
        PracticeSession(id, Instant.parse(at), seconds, Instrument.VIOLIN, null, false)

    private fun weekly(now: String, zone: ZoneId = ZoneOffset.UTC) = WeeklyPracticeTotalUseCase(
        ObservePracticeHistoryUseCase(auth, repo),
        Clock.fixed(Instant.parse(now), ZoneOffset.UTC)
    ) {
        zone
    }

    @Test
    fun `el historial se ordena por startedAt desc, pide 200 y usa el uid de la sesion`() = runTest {
        repo.seed(
            "u1",
            session("a", "2026-03-01T10:00:00Z", 60),
            session("c", "2026-03-03T10:00:00Z", 60),
            session("b", "2026-03-02T10:00:00Z", 60)
        )
        assertEquals(listOf("c", "b", "a"), ObservePracticeHistoryUseCase(auth, repo)().first().map { it.id })
        assertEquals(200, repo.lastLimit)
        assertEquals(listOf("observe:u1"), repo.calls)
    }

    @Test
    fun `el historial recorta a 200 y sin sesion esta vacio`() = runTest {
        repo.seed("u1", *Array(250) { session("s$it", "2026-03-01T10:00:00Z", 60) })
        assertEquals(200, ObservePracticeHistoryUseCase(auth, repo)().first().size)
        auth.user = null
        assertTrue(ObservePracticeHistoryUseCase(auth, repo)().first().isEmpty())
    }

    @Test
    fun `actualizar notas valida, recorta y vacio es null`() = runTest {
        val update = UpdatePracticeNotesUseCase(auth, repo)
        assertEquals(PracticeFailure.NotesTooLong, update("s1", "a".repeat(501)).exceptionOrNull())
        assertTrue(repo.calls.isEmpty())
        update("s1", " hi ").getOrThrow()
        update("s1", "  ").getOrThrow()
        assertEquals(listOf("updateNotes:u1:s1:hi", "updateNotes:u1:s1:null"), repo.calls)
        auth.user = null
        assertEquals(PracticeFailure.NoSession, update("s1", "x").exceptionOrNull())
    }

    @Test
    fun `borrar usa el uid de la sesion y sin sesion falla NoSession`() = runTest {
        val delete = DeletePracticeSessionUseCase(auth, repo)
        delete("s1").getOrThrow()
        assertEquals(listOf("delete:u1:s1"), repo.calls)
        auth.user = null
        assertEquals(PracticeFailure.NoSession, delete("s1").exceptionOrNull())
    }

    @Test
    fun `semana ISO suma esta semana y no la pasada, vacio es 0`() = runTest {
        assertEquals(0, weekly("2026-03-11T12:00:00Z")().first())
        repo.seed(
            "u1",
            session("a", "2026-03-09T08:00:00Z", 600),
            session("b", "2026-03-11T08:00:00Z", 1200),
            session("c", "2026-03-08T08:00:00Z", 1800)
        )
        assertEquals(1800, weekly("2026-03-11T12:00:00Z")().first())
    }

    @Test
    fun `limites domingo 23 59 59 y lunes 00 00 00`() = runTest {
        repo.seed(
            "u1",
            session("sun", "2026-03-08T23:59:59Z", 100),
            session("mon", "2026-03-09T00:00:00Z", 7)
        )
        assertEquals(7, weekly("2026-03-12T00:00:00Z")().first())
        assertEquals(100, weekly("2026-03-08T23:59:59Z")().first())
    }

    @Test
    fun `la semana usa la zona del reloj`() = runTest {
        // domingo 23:30 UTC ya es lunes 01:30 en UTC+2
        repo.seed("u1", session("a", "2026-03-08T23:30:00Z", 500))
        val plus2 = ZoneOffset.ofHours(2)
        assertEquals(500, weekly("2026-03-10T12:00:00Z", plus2)().first())
        assertEquals(0, weekly("2026-03-10T12:00:00Z", ZoneOffset.UTC)().first())
    }

    @Test
    fun `el cambio de horario no desplaza el lunes`() = runTest {
        val madrid = ZoneId.of("Europe/Madrid")
        // 2026-03-29 es el cambio a verano: lunes 30 00:00 local = 2026-03-29T22:00Z
        repo.seed(
            "u1",
            session("sun", "2026-03-29T21:59:59Z", 100),
            session("mon", "2026-03-29T22:00:00Z", 7)
        )
        assertEquals(7, weekly("2026-04-01T10:00:00Z", madrid)().first())
    }
}
