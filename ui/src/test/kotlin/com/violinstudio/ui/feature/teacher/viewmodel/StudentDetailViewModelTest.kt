package com.violinstudio.ui.feature.teacher.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.link.failure.LinkFailure
import com.violinstudio.domain.feature.link.model.TeacherLink
import com.violinstudio.domain.feature.link.usecase.ObserveMyStudentsUseCase
import com.violinstudio.domain.feature.link.usecase.ObserveStudentPracticeUseCase
import com.violinstudio.domain.feature.practice.failure.PracticeFailure
import com.violinstudio.domain.feature.practice.model.PracticeSession
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.ui.commons.flow.SETTLE_MS
import com.violinstudio.ui.commons.testing.MainDispatcherExtension
import com.violinstudio.ui.commons.testing.MviScenario
import com.violinstudio.ui.commons.testing.testMvi
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

private typealias DetailScenario = MviScenario<StudentDetailState, StudentDetailIntent, StudentDetailEffect>

@ExtendWith(MainDispatcherExtension::class)
@OptIn(ExperimentalCoroutinesApi::class)
class StudentDetailViewModelTest {
    private val observeStudents = mockk<ObserveMyStudentsUseCase>()
    private val observePractice = mockk<ObserveStudentPracticeUseCase>()
    private val students = MutableSharedFlow<List<TeacherLink>>(replay = 1)
    private val practice = MutableSharedFlow<List<PracticeSession>>(replay = 1)
    private val clock = Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), ZoneOffset.UTC)
    private val ana =
        TeacherLink("t1", "s1", "Marta", "Ana", Instrument.VIOLIN, 1, Instant.parse("2026-10-01T10:00:00Z"))
    private val leo =
        TeacherLink("t1", "s2", "Marta", "Leo", Instrument.CELLO, 1, Instant.parse("2026-10-01T10:00:00Z"))

    private val created = mutableListOf<StudentDetailViewModel>()

    @AfterEach
    fun cancelScopes() = created.forEach { it.viewModelScope.cancel() }

    private fun session(id: String, startedAt: String, sec: Int, notes: String? = null) =
        PracticeSession(id, Instant.parse(startedAt), sec, Instrument.VIOLIN, notes, false)

    private val thisWeek = session("a", "2026-10-13T09:00:00Z", 600, "escalas")
    private val older = session("b", "2026-10-05T09:00:00Z", 900)

    private fun create(uid: String = "s1"): StudentDetailViewModel {
        every { observeStudents() } returns students
        every { observePractice("s1") } returns practice
        return StudentDetailViewModel(
            SavedStateHandle(mapOf("studentUid" to uid)),
            observeStudents,
            observePractice,
            clock
        )
            .also { created += it }
    }

    private suspend fun DetailScenario.loaded() {
        students.emit(listOf(ana, leo))
        assertState { it.link == ana && it.isLoading }
        practice.emit(listOf(thisWeek, older))
        assertState { !it.isLoading && it.sessions == listOf(thisWeek, older) && it.weeklyTotalSec == 600 }
    }

    @Test
    fun `muestra el alumno, el historial con notas y el total semanal`() = runTest {
        create().testMvi {
            loaded()
        }
    }

    @Test
    fun `no hay intents de edicion ni borrado`() {
        // Solo lectura: la unica intencion es reintentar.
        val intents = StudentDetailIntent::class.sealedSubclasses.map { it.simpleName }.toSet()
        assertTrue(intents == setOf("Retry"), "intents: $intents")
    }

    @Test
    fun `un historial vacio espera el margen antes de mostrar el vacio`() = runTest {
        val vm = create()
        students.emit(listOf(ana))
        practice.emit(emptyList())
        runCurrent()
        advanceTimeBy(SETTLE_MS - 1)
        assertTrue(vm.state.value.isLoading)
        advanceTimeBy(2)
        runCurrent()
        assertFalse(vm.state.value.isLoading)
        assertTrue(vm.state.value.sessions.isEmpty())
        assertFalse(vm.state.value.unavailable)
    }

    @Test
    fun `una sesion nueva actualiza el historial y el total en caliente`() = runTest {
        create().testMvi {
            loaded()
            practice.emit(listOf(session("c", "2026-10-14T08:00:00Z", 300), thisWeek, older))
            assertState { it.sessions.size == 3 && it.weeklyTotalSec == 900 }
        }
    }

    @Test
    fun `un alumno sin vinculo muestra no disponible y nunca lee su registro`() = runTest {
        val vm = create("s9")
        students.emit(listOf(ana, leo))
        runCurrent()
        assertTrue(vm.state.value.isLoading)
        advanceTimeBy(SETTLE_MS + 1)
        runCurrent()
        val state = vm.state.value
        assertTrue(!state.isLoading && state.unavailable && state.link == null && state.sessions.isEmpty())
        io.mockk.verify(exactly = 0) { observePractice(any()) }
    }

    @Test
    fun `sin argumento de ruta tambien es no disponible`() = runTest {
        every { observeStudents() } returns students
        val vm = StudentDetailViewModel(SavedStateHandle(), observeStudents, observePractice, clock)
            .also { created += it }
        runCurrent()
        assertTrue(vm.state.value.unavailable && !vm.state.value.isLoading)
        io.mockk.verify(exactly = 0) { observePractice(any()) }
    }

    @Test
    fun `permission-denied tras desvincular vacia la vista y navega atras`() = runTest {
        // El caso de uso emite una lista vacia y termina el flujo cuando las reglas deniegan.
        val deny = kotlinx.coroutines.CompletableDeferred<Unit>()
        val denied = flow {
            emit(listOf(thisWeek))
            deny.await()
            emit(emptyList())
        }
        every { observeStudents() } returns students
        every { observePractice("s1") } returns denied
        val vm = StudentDetailViewModel(
            SavedStateHandle(mapOf("studentUid" to "s1")),
            observeStudents,
            observePractice,
            clock
        )
            .also { created += it }
        vm.testMvi {
            students.emit(listOf(ana))
            assertState { it.link == ana }
            assertState { it.sessions == listOf(thisWeek) }
            deny.complete(Unit)
            // El vacio del caso de uso llega antes del fin del flujo; despues se retira tambien el alumno.
            assertState { it.sessions.isEmpty() && it.link == ana }
            assertState { it.sessions.isEmpty() && it.link == null && it.weeklyTotalSec == 0 && !it.isLoading }
            assertEffect(StudentDetailEffect.NavigateBack)
        }
    }

    @Test
    fun `desaparecer el vinculo en caliente vacia la vista y navega atras`() = runTest {
        create().testMvi {
            loaded()
            students.emit(listOf(leo))
            assertState { it.link == null && it.sessions.isEmpty() && it.weeklyTotalSec == 0 }
            assertEffect(StudentDetailEffect.NavigateBack)
        }
    }

    @Test
    fun `otro fallo del historial ofrece reintentar y no el vacio`() = runTest {
        every { observeStudents() } returns students
        every { observePractice("s1") } returns flow { throw PracticeFailure.Unknown }
        val vm = StudentDetailViewModel(
            SavedStateHandle(mapOf("studentUid" to "s1")),
            observeStudents,
            observePractice,
            clock
        )
            .also { created += it }
        vm.testMvi {
            students.emit(listOf(ana))
            assertState { it.link == ana }
            assertState { it.loadFailed && !it.isLoading }
        }
    }

    @Test
    fun `reintentar vuelve a observar y recupera el historial`() = runTest {
        var attempt = 0
        every { observeStudents() } returns students
        every { observePractice("s1") } answers { if (attempt++ == 0) flow { throw LinkFailure.Unknown } else practice }
        val vm = StudentDetailViewModel(
            SavedStateHandle(mapOf("studentUid" to "s1")),
            observeStudents,
            observePractice,
            clock
        )
            .also { created += it }
        vm.testMvi {
            students.emit(listOf(ana))
            assertState { it.link == ana }
            assertState { it.loadFailed }
            intent(StudentDetailIntent.Retry)
            assertState { it.isLoading && !it.loadFailed }
            practice.emit(listOf(thisWeek))
            assertState { !it.isLoading && it.sessions == listOf(thisWeek) }
        }
    }

    @Test
    fun `el estado no vuelca nombres ni notas en toString`() = runTest {
        val vm = create()
        vm.testMvi { loaded() }
        val text = vm.state.value.toString()
        assertFalse(text.contains("Ana") || text.contains("escalas") || text.contains("s1"))
    }
}
