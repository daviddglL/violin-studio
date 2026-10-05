package com.violinstudio.domain.feature.practice.usecase

import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.FakeProfileRepository
import com.violinstudio.domain.feature.practice.failure.PracticeFailure
import com.violinstudio.domain.feature.practice.model.RunningSession
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.userProfile
import com.violinstudio.domain.feature.verifiedUser
import com.violinstudio.domain.testing.FakePracticeLogRepository
import com.violinstudio.domain.testing.FakeRunningSessionStore
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PracticeSessionUseCasesTest {
    private val t0 = Instant.parse("2026-03-10T10:00:00Z")
    private var now = t0
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }
    private val auth = FakeAuthRepository().apply { user = verifiedUser }
    private val profiles = FakeProfileRepository().apply { profile.value = userProfile() }
    private val store = FakeRunningSessionStore()
    private val repo = FakePracticeLogRepository()
    private val start = StartPracticeSessionUseCase(auth, profiles, store, clock)
    private val stop = StopPracticeSessionUseCase(auth, store, repo, clock) { "uuid-1" }

    @Test
    fun `iniciar usa el instrumento del perfil y guarda el inicio`() = runTest {
        val running = start().getOrThrow()
        assertEquals(RunningSession(t0, Instrument.VIOLIN), running)
        assertEquals(running, store.observe("u1").first())
    }

    @Test
    fun `el instrumento se puede cambiar sin tocar el perfil`() = runTest {
        assertEquals(Instrument.CELLO, start(Instrument.CELLO).getOrThrow().instrument)
        assertEquals(Instrument.VIOLIN, profiles.profile.value?.instrument)
        assertTrue(profiles.calls.none { it == "update" })
    }

    @Test
    fun `iniciar con una en curso falla AlreadyRunning y conserva la actual`() = runTest {
        start().getOrThrow()
        now = t0.plusSeconds(30)
        assertEquals(PracticeFailure.AlreadyRunning, start(Instrument.VIOLA).exceptionOrNull())
        assertEquals(RunningSession(t0, Instrument.VIOLIN), store.observe("u1").first())
    }

    @Test
    fun `sin sesion o sin perfil iniciar falla tipado`() = runTest {
        auth.user = null
        assertEquals(PracticeFailure.NoSession, start().exceptionOrNull())
        auth.user = verifiedUser
        profiles.profile.value = null
        assertEquals(PracticeFailure.NoSession, start().exceptionOrNull())
        assertEquals(Instrument.OTHER, start(Instrument.OTHER).getOrThrow().instrument)
    }

    @Test
    fun `parar a los 90 s escribe durationSec 90 y limpia la sesion en curso`() = runTest {
        start().getOrThrow()
        now = t0.plusSeconds(90)
        val result = stop("  bien  ").getOrThrow()
        assertFalse(result.clamped)
        assertEquals(90, result.draft.durationSec)
        assertEquals("bien", result.draft.notes)
        assertEquals("uuid-1", result.draft.id)
        assertEquals(listOf("u1" to result.draft), repo.created)
        assertNull(store.observe("u1").first())
    }

    @Test
    fun `parar sin sesion falla NotRunning sin escribir`() = runTest {
        assertEquals(PracticeFailure.NotRunning, stop().exceptionOrNull())
        assertTrue(repo.calls.isEmpty())
    }

    @Test
    fun `menos de 1 s es TooShort, no escribe y descarta la sesion`() = runTest {
        start().getOrThrow()
        now = t0.plusMillis(999)
        assertEquals(PracticeFailure.TooShort, stop().exceptionOrNull())
        assertTrue(repo.created.isEmpty())
        assertNull(store.observe("u1").first())
    }

    @Test
    fun `mas de 12 h se recorta a 43200 y avisa`() = runTest {
        start().getOrThrow()
        now = t0.plusSeconds(13 * 3600L)
        val result = stop().getOrThrow()
        assertTrue(result.clamped)
        assertEquals(43_200, result.draft.durationSec)
    }

    @Test
    fun `si la escritura falla o las notas son largas la sesion en curso se conserva`() = runTest {
        start().getOrThrow()
        now = t0.plusSeconds(60)
        assertEquals(PracticeFailure.NotesTooLong, stop("a".repeat(501)).exceptionOrNull())
        repo.createFailure = PracticeFailure.PermissionDenied
        assertEquals(PracticeFailure.PermissionDenied, stop().exceptionOrNull())
        assertEquals(RunningSession(t0, Instrument.VIOLIN), store.observe("u1").first())
    }

    @Test
    fun `la sesion en curso sobrevive a reiniciar el proceso y no la ve otro uid`() = runTest {
        start().getOrThrow()
        now = t0.plusSeconds(120)
        val restarted = ObserveRunningSessionUseCase(auth, store)
        assertEquals(t0, restarted().first()?.startedAt)
        auth.user = verifiedUser.copy(uid = "u2")
        assertNull(restarted().first())
        auth.user = null
        assertNull(restarted().first())
    }

    @Test
    fun `descartar limpia la sesion del uid`() = runTest {
        start().getOrThrow()
        DiscardRunningSessionUseCase(auth, store)().getOrThrow()
        assertEquals(listOf("u1"), store.cleared)
        assertTrue(repo.calls.isEmpty())
    }
}
