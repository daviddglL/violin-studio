package com.violinstudio.ui.feature.assignment.viewmodel

import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.assignment.failure.AssignmentFailure
import com.violinstudio.domain.feature.assignment.model.Assignment
import com.violinstudio.domain.feature.assignment.model.AssignmentStatus
import com.violinstudio.domain.feature.assignment.usecase.CompleteAssignmentUseCase
import com.violinstudio.domain.feature.assignment.usecase.ObserveMyAssignmentsUseCase
import com.violinstudio.domain.feature.assignment.usecase.ReopenAssignmentUseCase
import com.violinstudio.ui.commons.flow.SETTLE_MS
import com.violinstudio.ui.commons.testing.MainDispatcherExtension
import com.violinstudio.ui.commons.testing.MviScenario
import com.violinstudio.ui.commons.testing.testMvi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

private typealias StudentScenario =
    MviScenario<StudentAssignmentsState, StudentAssignmentsIntent, StudentAssignmentsEffect>

@ExtendWith(MainDispatcherExtension::class)
@OptIn(ExperimentalCoroutinesApi::class)
class StudentAssignmentsViewModelTest {
    private val observe = mockk<ObserveMyAssignmentsUseCase>()
    private val complete = mockk<CompleteAssignmentUseCase>()
    private val reopen = mockk<ReopenAssignmentUseCase>()
    private val list = MutableSharedFlow<List<Assignment>>(replay = 1)

    private fun task(id: String, status: AssignmentStatus = AssignmentStatus.OPEN, pending: Boolean = false) =
        Assignment(
            id,
            "t1",
            "Escalas $id",
            "Do mayor",
            null,
            status,
            if (status == AssignmentStatus.DONE) Instant.parse("2026-10-10T10:00:00Z") else null,
            Instant.parse("2026-10-01T10:00:00Z"),
            pending
        )

    private val created = mutableListOf<StudentAssignmentsViewModel>()

    @AfterEach
    fun cancelScopes() = created.forEach { it.viewModelScope.cancel() }

    private fun viewModel(): StudentAssignmentsViewModel {
        every { observe() } returns list
        return StudentAssignmentsViewModel(observe, complete, reopen).also { created += it }
    }

    private suspend fun StudentScenario.loaded(vararg tasks: Assignment) {
        list.emit(tasks.toList())
        assertState { !it.isLoading && it.assignments == tasks.toList() }
    }

    @Test
    fun `muestra las tareas en el orden del caso de uso, abiertas primero`() = runTest {
        val open = task("a")
        val done = task("b", AssignmentStatus.DONE)
        viewModel().testMvi { loaded(open, done) }
    }

    @Test
    fun `la primera lista vacia mantiene la carga durante el margen y luego muestra el vacio`() = runTest {
        val vm = viewModel()
        list.emit(emptyList())
        runCurrent()
        advanceTimeBy(SETTLE_MS - 1)
        assertTrue(vm.state.value.isLoading)
        advanceTimeBy(2)
        runCurrent()
        assertFalse(vm.state.value.isLoading)
        assertTrue(vm.state.value.assignments.isEmpty())
    }

    @Test
    fun `marcar hecha llama a Complete solo con el id, sin fecha del cliente`() = runTest {
        coEvery { complete(any()) } returns Result.success(Unit)
        viewModel().testMvi {
            loaded(task("a"))
            intent(StudentAssignmentsIntent.Complete("a"))
            assertState { it.busyIds == setOf("a") }
            assertState { it.busyIds.isEmpty() }
            assertNoEffects()
        }
        // doneAt lo pone el servidor (request.time): el ViewModel no pasa ninguna fecha.
        coVerify(exactly = 1) { complete("a") }
        coVerify(exactly = 0) { reopen(any()) }
    }

    @Test
    fun `reabrir llama a Reopen y solo sobre una tarea hecha`() = runTest {
        coEvery { reopen(any()) } returns Result.success(Unit)
        viewModel().testMvi {
            loaded(task("a"), task("b", AssignmentStatus.DONE))
            intent(StudentAssignmentsIntent.Reopen("a"))
            intent(StudentAssignmentsIntent.Reopen("b"))
            assertState { it.busyIds == setOf("b") }
            assertState { it.busyIds.isEmpty() }
            runCurrent()
            assertNoEffects()
        }
        coVerify(exactly = 1) { reopen("b") }
    }

    @Test
    fun `completar una tarea que ya esta hecha o que no existe no escribe`() = runTest {
        viewModel().testMvi {
            loaded(task("b", AssignmentStatus.DONE))
            intent(StudentAssignmentsIntent.Complete("b"))
            intent(StudentAssignmentsIntent.Complete("ajena"))
            runCurrent()
            assertNoEffects()
        }
        coVerify(exactly = 0) { complete(any()) }
    }

    @Test
    fun `mientras una escritura de la misma tarea esta en curso se ignora otro toque`() = runTest {
        coEvery { complete(any()) } coAnswers {
            delay(100)
            Result.success(Unit)
        }
        viewModel().testMvi {
            loaded(task("a"))
            intent(StudentAssignmentsIntent.Complete("a"))
            intent(StudentAssignmentsIntent.Complete("a"))
            assertState { it.busyIds == setOf("a") }
            assertState { it.busyIds.isEmpty() }
        }
        coVerify(exactly = 1) { complete("a") }
    }

    @Test
    fun `el cambio local aparece como pendiente de sincronizar y no bloquea la interfaz`() = runTest {
        coEvery { complete(any()) } returns Result.success(Unit)
        viewModel().testMvi {
            loaded(task("a"))
            intent(StudentAssignmentsIntent.Complete("a"))
            assertState { it.busyIds == setOf("a") }
            assertState { it.busyIds.isEmpty() }
            // El SDK reemite la tarea hecha con pendingSync = true (cache offline) sin esperar al servidor.
            val pending = task("a", AssignmentStatus.DONE, pending = true)
            loaded(pending)
            assertTrue(pending.pendingSync)
        }
    }

    @Test
    fun `tras escribir con exito un segundo toque se ignora hasta que la lista refleja el cambio`() = runTest {
        coEvery { complete(any()) } returns Result.success(Unit)
        coEvery { reopen(any()) } returns Result.success(Unit)
        viewModel().testMvi {
            loaded(task("a"))
            intent(StudentAssignmentsIntent.Complete("a"))
            assertState { it.busyIds == setOf("a") }
            assertState { it.busyIds.isEmpty() }
            // La lista aun no ha reemitido: el estado local sigue como OPEN, pero no se vuelve a escribir.
            intent(StudentAssignmentsIntent.Complete("a"))
            assertNoEffects()
            coVerify(exactly = 1) { complete("a") }
            // El SDK reemite la tarea hecha: la guarda se libera y se puede reabrir.
            loaded(task("a", AssignmentStatus.DONE, pending = true))
            intent(StudentAssignmentsIntent.Reopen("a"))
            assertState { it.busyIds == setOf("a") }
            assertState { it.busyIds.isEmpty() }
        }
        coVerify(exactly = 1) { reopen("a") }
    }

    @Test
    fun `si la lista ya refleja el cambio antes de acabar la escritura la guarda se libera al terminar`() = runTest {
        coEvery { complete(any()) } coAnswers {
            delay(100)
            Result.success(Unit)
        }
        coEvery { reopen(any()) } returns Result.success(Unit)
        viewModel().testMvi {
            loaded(task("a"))
            intent(StudentAssignmentsIntent.Complete("a"))
            assertState { it.busyIds == setOf("a") }
            // El SDK reemite la tarea hecha mientras la escritura sigue en curso (aun no hay guarda de espera).
            loaded(task("a", AssignmentStatus.DONE, pending = true))
            assertState { it.busyIds.isEmpty() }
            intent(StudentAssignmentsIntent.Reopen("a"))
            assertState { it.busyIds == setOf("a") }
            assertState { it.busyIds.isEmpty() }
        }
        coVerify(exactly = 1) { reopen("a") }
    }

    @Test
    fun `si la lista nunca refleja el cambio la guarda se libera pasado el margen`() = runTest {
        coEvery { complete(any()) } returns Result.success(Unit)
        viewModel().testMvi {
            loaded(task("a"))
            intent(StudentAssignmentsIntent.Complete("a"))
            assertState { it.busyIds == setOf("a") }
            assertState { it.busyIds.isEmpty() }
            advanceTimeBy(SETTLE_MS + 1)
            runCurrent()
            intent(StudentAssignmentsIntent.Complete("a"))
            assertState { it.busyIds == setOf("a") }
            assertState { it.busyIds.isEmpty() }
        }
        coVerify(exactly = 2) { complete("a") }
    }

    @Test
    fun `un fallo al escribir muestra el error y se descarta`() = runTest {
        coEvery { complete(any()) } returns Result.failure(AssignmentFailure.PermissionDenied)
        viewModel().testMvi {
            loaded(task("a"))
            intent(StudentAssignmentsIntent.Complete("a"))
            assertState { it.busyIds == setOf("a") }
            assertState { it.error == StudentAssignmentsError.PERMISSION_DENIED }
            intent(StudentAssignmentsIntent.DismissError)
            assertState { it.error == null }
        }
    }

    @Test
    fun `una excepcion del caso de uso libera la tarea en finally y no mata los intents`() = runTest {
        coEvery { complete("a") } throws IllegalStateException("boom")
        coEvery { complete("b") } returns Result.success(Unit)
        viewModel().testMvi {
            loaded(task("a"), task("b"))
            intent(StudentAssignmentsIntent.Complete("a"))
            assertState { it.busyIds == setOf("a") }
            assertState { it.busyIds.isEmpty() && it.error == StudentAssignmentsError.UNKNOWN }
            intent(StudentAssignmentsIntent.Complete("b"))
            assertState { it.busyIds == setOf("b") }
            assertState { it.busyIds.isEmpty() }
        }
        coVerify(exactly = 1) { complete("b") }
    }

    @Test
    fun `un fallo del observador marca loadFailed y Retry vuelve a escuchar`() = runTest {
        var attempts = 0
        every { observe() } answers {
            if (attempts++ == 0) flow { throw AssignmentFailure.Unknown } else list
        }
        val vm = StudentAssignmentsViewModel(observe, complete, reopen).also { created += it }
        vm.testMvi {
            assertState { it.loadFailed && !it.isLoading }
            intent(StudentAssignmentsIntent.Retry)
            assertState { it.isLoading && !it.loadFailed }
            loaded(task("a"))
        }
        assertEquals(2, attempts)
    }

    @Test
    fun `toString no incluye titulos ni descripciones`() {
        val state = StudentAssignmentsState(assignments = listOf(task("a")))
        assertFalse(state.toString().contains("Escalas"))
        assertFalse(state.toString().contains("Do mayor"))
    }
}
