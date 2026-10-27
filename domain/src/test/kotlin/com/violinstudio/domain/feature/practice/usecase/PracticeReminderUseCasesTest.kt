package com.violinstudio.domain.feature.practice.usecase

import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.practice.failure.PracticeFailure
import com.violinstudio.domain.feature.practice.model.PracticeReminder
import com.violinstudio.domain.feature.practice.model.PracticeSession
import com.violinstudio.domain.feature.practice.model.RunningSession
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.verifiedUser
import com.violinstudio.domain.testing.FakePracticeLogRepository
import com.violinstudio.domain.testing.FakePracticeReminderScheduler
import com.violinstudio.domain.testing.FakePracticeReminderStore
import com.violinstudio.domain.testing.FakeRunningSessionStore
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PracticeReminderUseCasesTest {
    private val madrid = ZoneId.of("Europe/Madrid")
    private val now = Instant.parse("2026-10-14T15:00:00Z") // 17:00 en Madrid
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val auth = FakeAuthRepository().apply { user = verifiedUser }
    private val store = FakePracticeReminderStore()
    private val scheduler = FakePracticeReminderScheduler()
    private val running = FakeRunningSessionStore()
    private val log = FakePracticeLogRepository()
    private val observe = ObservePracticeReminderUseCase(auth, store)
    private val on = PracticeReminder(enabled = true, time = LocalTime.of(18, 0))

    private fun due() = PracticeReminderDueUseCase(
        auth,
        store,
        running,
        ObservePracticeHistoryUseCase(auth, log),
        scheduler,
        clock
    ) { madrid }

    private fun session(at: String) = PracticeSession(at, Instant.parse(at), 600, Instrument.VIOLIN, null, false)

    @Test
    fun `la siguiente hora es hoy si aun no ha pasado y si no manana`() {
        assertEquals(Duration.ofHours(1), PracticeReminder.delayUntilNext(now, madrid, LocalTime.of(18, 0)))
        assertEquals(Duration.ofHours(23), PracticeReminder.delayUntilNext(now, madrid, LocalTime.of(16, 0)))
        assertEquals(Duration.ofDays(1), PracticeReminder.delayUntilNext(now, madrid, LocalTime.of(17, 0)))
    }

    @Test
    fun `con margen el aviso que acaba de saltar pasa al dia siguiente`() {
        val atSix = Instant.parse("2026-10-14T16:00:10Z")
        val delay = PracticeReminder.delayUntilNext(atSix, madrid, LocalTime.of(18, 0), Duration.ofMinutes(1))
        assertEquals(Duration.ofDays(1).minusSeconds(10), delay)
    }

    @Test
    fun `sigue la hora local en el cambio de hora`() {
        val beforeChange = Instant.parse("2026-10-24T16:00:00Z") // sabado 18:00 CEST; el domingo es CET
        val delay = PracticeReminder.delayUntilNext(beforeChange, madrid, LocalTime.of(18, 0), Duration.ofMinutes(1))
        assertEquals(Duration.ofHours(25), delay)
    }

    @Test
    fun `sin sesion el recordatorio esta apagado y no se puede guardar`() = runTest {
        auth.user = null
        assertEquals(PracticeReminder(), observe().first())
        assertEquals(PracticeFailure.NoSession, SetPracticeReminderUseCase(auth, store)(on).exceptionOrNull())
    }

    @Test
    fun `guardar falla tipado si el almacen falla`() = runTest {
        store.failure = IllegalStateException("io")
        assertEquals(PracticeFailure.Unknown, SetPracticeReminderUseCase(auth, store)(on).exceptionOrNull())
    }

    @Test
    fun `sync programa al encender, reprograma al cambiar la hora y anula al apagar o cerrar sesion`() = runTest {
        val sync = SyncPracticeReminderUseCase(observe, scheduler, clock) { madrid }
        val job = launch { sync() }
        runCurrent()
        assertEquals(listOf("cancel"), scheduler.calls)
        SetPracticeReminderUseCase(auth, store)(on)
        runCurrent()
        SetPracticeReminderUseCase(auth, store)(on.copy(time = LocalTime.of(19, 0)))
        runCurrent()
        auth.user = null
        runCurrent()
        assertEquals(listOf("cancel", "schedule:3600", "schedule:7200", "cancel"), scheduler.calls)
        job.cancel()
    }

    @Test
    fun `al saltar sin practica hoy avisa y deja programado el de manana`() = runTest {
        store.set("u1", on)
        log.seed("u1", session("2026-10-13T10:00:00Z"))
        assertTrue(due()())
        assertEquals(listOf("next:${Duration.ofHours(1).seconds}"), scheduler.calls)
    }

    @Test
    fun `con practica hoy o una sesion en curso no avisa pero reprograma`() = runTest {
        store.set("u1", on)
        log.seed("u1", session("2026-10-14T07:00:00Z"))
        assertFalse(due()())
        log.seed("u1")
        running.start("u1", RunningSession("r", now, Instrument.VIOLIN))
        assertFalse(due()())
        assertEquals(2, scheduler.calls.count { it.startsWith("next") })
    }

    @Test
    fun `apagado o sin sesion no avisa ni reprograma`() = runTest {
        assertFalse(due()())
        store.set("u1", on)
        auth.user = null
        assertFalse(due()())
        assertTrue(scheduler.calls.isEmpty())
    }
}
