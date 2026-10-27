package com.violinstudio.domain.feature.practice.usecase

import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.FakeProfileRepository
import com.violinstudio.domain.feature.practice.failure.PracticeFailure
import com.violinstudio.domain.feature.practice.model.RunningSession
import com.violinstudio.domain.feature.practice.model.ToolPractice
import com.violinstudio.domain.feature.practice.model.ToolPracticeTimer
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.userProfile
import com.violinstudio.domain.feature.verifiedUser
import com.violinstudio.domain.testing.FakePracticeLogRepository
import com.violinstudio.domain.testing.FakeRunningSessionStore
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolPracticeTest {
    private val t0 = Instant.parse("2026-10-14T17:00:00Z")
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
    private val log = LogToolPracticeUseCase(auth, profiles, store, repo, clock, PracticeSessionLock()) { "tool-1" }

    @Test
    fun `suma solo los tramos activos y ofrece desde el primer arranque al llegar a 1 min`() {
        val timer = ToolPracticeTimer(clock)
        timer.start()
        now = t0.plusSeconds(40)
        timer.stop()
        assertNull(timer.offer())
        now = t0.plusSeconds(300) // pausa de 260 s que no cuenta
        timer.start()
        assertNull(timer.offer()) // en marcha no se ofrece
        timer.start() // idempotente
        now = t0.plusSeconds(330)
        timer.stop()
        timer.stop()
        assertEquals(ToolPractice(t0, 70), timer.offer())
        timer.reset()
        assertNull(timer.offer())
    }

    @Test
    fun `guardar escribe una sesion con el instrumento del perfil y sin notas`() = runTest {
        val draft = log(ToolPractice(t0.minusSeconds(600), 540)).getOrThrow()
        assertEquals("tool-1", draft.id)
        assertEquals(540, draft.durationSec)
        assertEquals(Instrument.VIOLIN, draft.instrument)
        assertNull(draft.notes)
        assertEquals(listOf("u1" to draft), repo.created)
    }

    @Test
    fun `con una sesion manual en curso o sin sesion no guarda`() = runTest {
        store.start("u1", RunningSession("r", t0, Instrument.VIOLIN))
        assertEquals(PracticeFailure.AlreadyRunning, log(ToolPractice(t0, 120)).exceptionOrNull())
        store.clear("u1")
        auth.user = null
        assertEquals(PracticeFailure.NoSession, log(ToolPractice(t0, 120)).exceptionOrNull())
        assertTrue(repo.created.isEmpty())
    }
}
