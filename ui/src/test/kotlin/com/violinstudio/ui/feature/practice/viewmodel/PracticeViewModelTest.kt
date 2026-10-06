package com.violinstudio.ui.feature.practice.viewmodel

import com.violinstudio.domain.feature.practice.failure.PracticeFailure
import com.violinstudio.domain.feature.practice.model.PracticeDraft
import com.violinstudio.domain.feature.practice.model.PracticeSession
import com.violinstudio.domain.feature.practice.model.RunningSession
import com.violinstudio.domain.feature.practice.usecase.DeletePracticeSessionUseCase
import com.violinstudio.domain.feature.practice.usecase.DiscardRunningSessionUseCase
import com.violinstudio.domain.feature.practice.usecase.ObservePracticeHistoryUseCase
import com.violinstudio.domain.feature.practice.usecase.ObserveRunningSessionUseCase
import com.violinstudio.domain.feature.practice.usecase.StartPracticeSessionUseCase
import com.violinstudio.domain.feature.practice.usecase.StopPracticeSessionUseCase
import com.violinstudio.domain.feature.practice.usecase.StoppedPractice
import com.violinstudio.domain.feature.practice.usecase.UpdatePracticeNotesUseCase
import com.violinstudio.domain.feature.practice.usecase.WeeklyPracticeTotalUseCase
import com.violinstudio.domain.feature.profile.model.ConsentStatus
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.profile.model.Role
import com.violinstudio.domain.feature.profile.model.UserProfile
import com.violinstudio.domain.feature.profile.usecase.ObserveProfileUseCase
import com.violinstudio.ui.commons.testing.MainDispatcherExtension
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

private class TestClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now
}

@ExtendWith(MainDispatcherExtension::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PracticeViewModelTest {
    private val t0 = Instant.parse("2026-01-05T10:00:00Z")
    private val clock = TestClock(t0)
    private val profile = UserProfile(
        "u1", "Ana", Instrument.VIOLA, "es", Role.INDEPENDENT, false, ConsentStatus.GRANTED, 1, null, false
    )
    private val session = MutableStateFlow<UserProfile?>(profile)
    private val running = MutableStateFlow<RunningSession?>(null)
    private val history = MutableStateFlow(emptyList<PracticeSession>())
    private var weeklyCalls = 0
    private var weeklyFailing = false
    private val observeProfile = mockk<ObserveProfileUseCase> { every { this@mockk() } returns session }
    private val observeRunning = mockk<ObserveRunningSessionUseCase> { every { this@mockk() } returns running }
    private val observeHistory = mockk<ObservePracticeHistoryUseCase> { every { this@mockk() } returns history }
    private val weekly = mockk<WeeklyPracticeTotalUseCase> {
        every { this@mockk() } answers {
            val n = ++weeklyCalls
            if (weeklyFailing) {
                flow { throw PracticeFailure.PermissionDenied }
            } else {
                flow {
                    emit(600 * n)
                    awaitCancellation()
                }
            }
        }
    }
    private val start = mockk<StartPracticeSessionUseCase>()
    private val stop = mockk<StopPracticeSessionUseCase>()
    private val discard = mockk<DiscardRunningSessionUseCase>()
    private val updateNotes = mockk<UpdatePracticeNotesUseCase>()
    private val delete = mockk<DeletePracticeSessionUseCase>()
    private val started = RunningSession("r1", t0, Instrument.VIOLA)

    private fun vm() = PracticeViewModel(
        observeProfile, observeRunning, observeHistory, weekly, start, stop, discard, updateNotes, delete, clock
    ) { ZoneOffset.UTC }

    /** Cierra la sesion al final: cancela ticker y temporizador semanal, que si no impiden terminar `runTest`. */
    private fun test(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            // Aunque falle una asercion: sin ticker vivo `runTest` no gira en bucle sobre tiempo virtual.
            session.value = null
            runCurrent()
        }
    }

    private fun TestScope.ready(): PracticeViewModel = vm().also { runCurrent() }

    private fun TestScope.advance(ms: Long) {
        clock.now = clock.now.plusMillis(ms)
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun draft(durationSec: Int = 90) =
        PracticeDraft.create("r1", t0, durationSec, Instrument.VIOLA, null, t0.plusSeconds(durationSec.toLong()))
            .getOrThrow()

    private fun saved() = PracticeSession("s1", t0, 60, Instrument.VIOLA, null, false)

    @Test
    fun `con sesion Ready carga instrumento del perfil, historial y total semanal`() = test {
        history.value = listOf(saved())
        val vm = ready()
        assertTrue(vm.state.value.ready)
        assertEquals(Instrument.VIOLA, vm.state.value.instrument)
        assertEquals(listOf(saved()), vm.state.value.history)
        assertEquals(600, vm.state.value.weeklyTotalSec)
    }

    @Test
    fun `Start sin tocar el selector usa el perfil y con selector guarda el elegido`() = test {
        coEvery { start(any()) } returns Result.success(started)
        val vm = ready()
        vm.onIntent(PracticeIntent.Start)
        runCurrent()
        coVerify { start(null) }
        running.value = started
        runCurrent()
        running.value = null
        runCurrent()
        vm.onIntent(PracticeIntent.SelectInstrument(Instrument.CELLO))
        runCurrent()
        vm.onIntent(PracticeIntent.Start)
        runCurrent()
        coVerify { start(Instrument.CELLO) }
    }

    @Test
    fun `el cronometro avanza con el reloj, incluso tras 60 s en segundo plano`() = test {
        running.value = started
        val vm = ready()
        advance(3_000)
        assertEquals(3, vm.state.value.elapsedSec)
        clock.now = clock.now.plusSeconds(60)
        advance(1_000)
        assertEquals(64, vm.state.value.elapsedSec)
    }

    @Test
    fun `un VM recreado con el mismo almacen restaura el cronometro`() = test {
        running.value = started
        clock.now = t0.plusSeconds(120)
        assertEquals(120, ready().state.value.elapsedSec)
    }

    @Test
    fun `dos toques en Start solo inician una sesion`() = test {
        val gate = CompletableDeferred<Result<RunningSession>>()
        coEvery { start(any()) } coAnswers { gate.await() }
        val vm = ready()
        vm.onIntent(PracticeIntent.Start)
        vm.onIntent(PracticeIntent.Start)
        runCurrent()
        gate.complete(Result.success(started))
        runCurrent()
        coVerify(exactly = 1) { start(any()) }
        assertEquals(started, vm.state.value.running)
    }

    @Test
    fun `Stop abre el dialogo, Save guarda con las notas y cierra`() = test {
        running.value = started
        coEvery { stop(any()) } returns Result.success(StoppedPractice(draft(), clamped = false))
        val vm = ready()
        vm.onIntent(PracticeIntent.Stop)
        vm.onIntent(PracticeIntent.EditNotes("buena sesion"))
        runCurrent()
        assertTrue(vm.state.value.showSave)
        vm.onIntent(PracticeIntent.Save)
        runCurrent()
        coVerify { stop("buena sesion") }
        assertFalse(vm.state.value.showSave)
        assertNull(vm.state.value.running)
    }

    @Test
    fun `descartar no escribe y notas de mas de 500 caracteres bloquean Save sin llamar`() = test {
        running.value = started
        coEvery { discard() } returns Result.success(Unit)
        val vm = ready()
        vm.onIntent(PracticeIntent.Stop)
        vm.onIntent(PracticeIntent.EditNotes("a".repeat(501)))
        vm.onIntent(PracticeIntent.Save)
        runCurrent()
        assertEquals(PracticeMessage.NOTES_TOO_LONG, vm.state.value.message)
        vm.onIntent(PracticeIntent.Discard)
        runCurrent()
        coVerify(exactly = 0) { stop(any()) }
        coVerify(exactly = 1) { discard() }
        assertFalse(vm.state.value.showSave)
    }

    @Test
    fun `TooShort y el recorte a 12 h dejan un mensaje en el estado`() = test {
        running.value = started
        coEvery { stop(any()) } returns Result.failure(PracticeFailure.TooShort) andThen
            Result.success(StoppedPractice(draft(43_200), clamped = true))
        val vm = ready()
        vm.onIntent(PracticeIntent.Stop)
        vm.onIntent(PracticeIntent.Save)
        runCurrent()
        assertEquals(PracticeMessage.TOO_SHORT, vm.state.value.message)
        vm.onIntent(PracticeIntent.DismissMessage)
        vm.onIntent(PracticeIntent.Stop)
        vm.onIntent(PracticeIntent.Save)
        runCurrent()
        assertEquals(PracticeMessage.CLAMPED, vm.state.value.message)
    }

    @Test
    fun `el total semanal se relanza el lunes a las 00 00 con la pantalla abierta`() = test {
        clock.now = Instant.parse("2026-01-11T23:59:50Z") // domingo
        val vm = ready()
        assertEquals(1, weeklyCalls)
        assertEquals(600, vm.state.value.weeklyTotalSec)
        advance(9_000)
        assertEquals(1, weeklyCalls)
        assertEquals(600, vm.state.value.weeklyTotalSec)
        advance(2_000)
        assertEquals(2, weeklyCalls)
        assertEquals(1_200, vm.state.value.weeklyTotalSec)
    }

    @Test
    fun `un fallo de permiso del historial no bloquea y Retry vuelve a escuchar`() = test {
        var failing = true
        every { observeHistory() } answers { if (failing) flow { throw PracticeFailure.PermissionDenied } else history }
        val vm = ready()
        assertEquals(PracticeMessage.PERMISSION_DENIED, vm.state.value.message)
        assertTrue(vm.state.value.history.isEmpty())
        failing = false
        history.value = listOf(saved())
        vm.onIntent(PracticeIntent.Retry)
        runCurrent()
        assertNull(vm.state.value.message)
        assertEquals(1, vm.state.value.history.size)
    }

    @Test
    fun `sin Ready se cancelan los flujos y no queda nada del usuario anterior`() = test {
        history.value = listOf(saved())
        running.value = started
        val vm = ready()
        session.value = null
        runCurrent()
        assertEquals(PracticeState(), vm.state.value)
        assertEquals(0, history.subscriptionCount.value)
        assertEquals(0, running.subscriptionCount.value)
        coVerify(exactly = 0) { stop(any()) }
        coVerify(exactly = 0) { discard() }
        coVerify(exactly = 0) { delete(any()) }
        coVerify(exactly = 0) { updateNotes(any(), any()) }
        coVerify(exactly = 0) { start(any()) }
    }

    @Test
    fun `borrar pide confirmacion, cancelar no llama y confirmar borra`() = test {
        coEvery { delete(any()) } returns Result.success(Unit)
        val vm = ready()
        vm.onIntent(PracticeIntent.RequestDelete("s1"))
        vm.onIntent(PracticeIntent.CancelDelete)
        runCurrent()
        coVerify(exactly = 0) { delete(any()) }
        vm.onIntent(PracticeIntent.RequestDelete("s1"))
        vm.onIntent(PracticeIntent.ConfirmDelete)
        runCurrent()
        coVerify { delete("s1") }
        assertNull(vm.state.value.confirmDeleteId)
    }

    @Test
    fun `editar notas llama al caso de uso y rechaza mas de 500 sin llamar`() = test {
        coEvery { updateNotes(any(), any()) } returns Result.success(Unit)
        val vm = ready()
        vm.onIntent(PracticeIntent.UpdateNotes("s1", "ok"))
        vm.onIntent(PracticeIntent.UpdateNotes("s1", "a".repeat(501)))
        runCurrent()
        coVerify(exactly = 1) { updateNotes(any(), any()) }
        assertEquals(PracticeMessage.NOTES_TOO_LONG, vm.state.value.message)
    }

    @Test
    fun `una emision en cola del usuario anterior no aparece tras cambiar de usuario`() = test {
        var calls = 0
        every { observeHistory() } answers { if (calls++ == 0) history else flow { awaitCancellation() } }
        val vm = ready()
        session.value = profile.copy(uid = "u2")
        history.value = listOf(saved())
        runCurrent()
        assertEquals(2, calls)
        assertTrue(vm.state.value.history.isEmpty())
    }

    @Test
    fun `si falla la sesion avisa y Retry vuelve a escucharla`() = test {
        every { observeProfile() } returns flow<UserProfile?> { throw IllegalStateException() } andThen session
        val vm = ready()
        assertFalse(vm.state.value.ready)
        assertEquals(PracticeMessage.UNKNOWN, vm.state.value.message)
        vm.onIntent(PracticeIntent.Retry)
        runCurrent()
        assertTrue(vm.state.value.ready)
        assertNull(vm.state.value.message)
        assertEquals(600, vm.state.value.weeklyTotalSec)
    }

    @Test
    fun `un cambio de instrumento en el perfil se refleja salvo que el usuario ya haya elegido`() = test {
        val vm = ready()
        session.value = profile.copy(instrument = Instrument.CELLO)
        runCurrent()
        assertEquals(Instrument.CELLO, vm.state.value.instrument)
        assertEquals(1, weeklyCalls)
        vm.onIntent(PracticeIntent.SelectInstrument(Instrument.VIOLIN))
        runCurrent()
        session.value = profile.copy(instrument = Instrument.OTHER)
        runCurrent()
        assertEquals(Instrument.VIOLIN, vm.state.value.instrument)
    }

    @Test
    fun `el cronometro se alinea con el siguiente segundo del reloj`() = test {
        clock.now = t0.plusMillis(400)
        running.value = started
        val vm = ready()
        assertEquals(0, vm.state.value.elapsedSec)
        advance(600)
        assertEquals(1, vm.state.value.elapsedSec)
    }

    @Test
    fun `un fallo del total semanal avisa sin crashear y Retry lo vuelve a escuchar`() = test {
        weeklyFailing = true
        val vm = ready()
        assertEquals(PracticeMessage.PERMISSION_DENIED, vm.state.value.message)
        assertEquals(0, vm.state.value.weeklyTotalSec)
        weeklyFailing = false
        vm.onIntent(PracticeIntent.Retry)
        runCurrent()
        assertNull(vm.state.value.message)
        assertEquals(2, weeklyCalls)
        assertEquals(1_200, vm.state.value.weeklyTotalSec)
    }

    @Test
    fun `un fallo del almacen de sesion en curso avisa y Retry lo vuelve a escuchar`() = test {
        var failing = true
        every { observeRunning() } answers { if (failing) flow { throw IllegalStateException() } else running }
        val vm = ready()
        assertEquals(PracticeMessage.UNKNOWN, vm.state.value.message)
        failing = false
        running.value = started
        vm.onIntent(PracticeIntent.Retry)
        runCurrent()
        assertNull(vm.state.value.message)
        assertEquals(started, vm.state.value.running)
    }

    @Test
    fun `Start se ignora si ya hay una sesion en curso`() = test {
        running.value = started
        val vm = ready()
        vm.onIntent(PracticeIntent.Start)
        runCurrent()
        coVerify(exactly = 0) { start(any()) }
    }

    @Test
    fun `un fallo al iniciar deja el estado libre y avisa`() = test {
        coEvery { start(any()) } returns Result.failure(PracticeFailure.AlreadyRunning)
        val vm = ready()
        vm.onIntent(PracticeIntent.Start)
        runCurrent()
        assertFalse(vm.state.value.busy)
        assertEquals(PracticeMessage.UNKNOWN, vm.state.value.message)
        coEvery { start(any()) } returns Result.failure(PracticeFailure.NoSession)
        vm.onIntent(PracticeIntent.Start)
        runCurrent()
        assertFalse(vm.state.value.busy)
        coVerify(exactly = 2) { start(any()) }
    }

    @Test
    fun `Save se ignora mientras hay una operacion en curso`() = test {
        running.value = started
        val gate = CompletableDeferred<Result<StoppedPractice>>()
        coEvery { stop(any()) } coAnswers { gate.await() }
        val vm = ready()
        vm.onIntent(PracticeIntent.Stop)
        vm.onIntent(PracticeIntent.Save)
        vm.onIntent(PracticeIntent.Save)
        runCurrent()
        assertTrue(vm.state.value.busy)
        coVerify(exactly = 1) { stop(any()) }
        gate.complete(Result.success(StoppedPractice(draft(), clamped = false)))
        runCurrent()
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `un fallo no fatal al guardar mantiene el dialogo y las notas`() = test {
        running.value = started
        coEvery { stop(any()) } returns Result.failure(IllegalStateException())
        val vm = ready()
        vm.onIntent(PracticeIntent.Stop)
        vm.onIntent(PracticeIntent.EditNotes("mis notas"))
        vm.onIntent(PracticeIntent.Save)
        runCurrent()
        assertTrue(vm.state.value.showSave)
        assertEquals("mis notas", vm.state.value.draftNotes)
        assertEquals(PracticeMessage.UNKNOWN, vm.state.value.message)
        assertFalse(vm.state.value.busy)
        assertEquals(started, vm.state.value.running)
    }

    @Test
    fun `si el almacen emite sin sesion con el dialogo abierto se cierra y se limpian las notas`() = test {
        running.value = started
        val vm = ready()
        vm.onIntent(PracticeIntent.Stop)
        vm.onIntent(PracticeIntent.EditNotes("pendiente"))
        runCurrent()
        assertTrue(vm.state.value.showSave)
        running.value = null
        runCurrent()
        assertFalse(vm.state.value.showSave)
        assertEquals("", vm.state.value.draftNotes)
        assertNull(vm.state.value.running)
    }

    @Test
    fun `un fallo del flujo de perfil queda como aviso reintentable`() = test {
        every { observeProfile() } returns flow { throw PracticeFailure.PermissionDenied }
        val vm = ready()
        assertEquals(PracticeMessage.PERMISSION_DENIED, vm.state.value.message)
        assertTrue(vm.state.value.retryable)
    }
}
