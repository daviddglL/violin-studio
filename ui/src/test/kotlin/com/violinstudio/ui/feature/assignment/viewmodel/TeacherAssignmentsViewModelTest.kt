package com.violinstudio.ui.feature.assignment.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.assignment.failure.AssignmentFailure
import com.violinstudio.domain.feature.assignment.model.Assignment
import com.violinstudio.domain.feature.assignment.model.AssignmentStatus
import com.violinstudio.domain.feature.assignment.usecase.DeleteAssignmentUseCase
import com.violinstudio.domain.feature.assignment.usecase.ObserveStudentAssignmentsUseCase
import com.violinstudio.ui.commons.flow.SETTLE_MS
import com.violinstudio.ui.commons.testing.MainDispatcherExtension
import com.violinstudio.ui.commons.testing.MviScenario
import com.violinstudio.ui.commons.testing.testMvi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

private typealias TeacherScenario =
    MviScenario<TeacherAssignmentsState, TeacherAssignmentsIntent, TeacherAssignmentsEffect>

@ExtendWith(MainDispatcherExtension::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TeacherAssignmentsViewModelTest {
    private val observe = mockk<ObserveStudentAssignmentsUseCase>()
    private val delete = mockk<DeleteAssignmentUseCase>()
    private val list = MutableSharedFlow<List<Assignment>>(replay = 1)

    private fun task(id: String, pending: Boolean = false, status: AssignmentStatus = AssignmentStatus.OPEN) =
        Assignment(
            id,
            "t1",
            "Escalas $id",
            "Do mayor",
            null,
            status,
            null,
            Instant.parse("2026-10-01T10:00:00Z"),
            pending
        )

    private val created = mutableListOf<TeacherAssignmentsViewModel>()

    @AfterEach
    fun cancelScopes() = created.forEach { it.viewModelScope.cancel() }

    private fun viewModel(uid: String? = "s1"): TeacherAssignmentsViewModel {
        every { observe("s1") } returns list
        val handle = SavedStateHandle(if (uid == null) emptyMap() else mapOf("studentUid" to uid))
        return TeacherAssignmentsViewModel(handle, observe, delete).also { created += it }
    }

    private fun viewModelWith(flow: kotlinx.coroutines.flow.Flow<List<Assignment>>): TeacherAssignmentsViewModel {
        every { observe("s1") } returns flow
        return TeacherAssignmentsViewModel(SavedStateHandle(mapOf("studentUid" to "s1")), observe, delete)
            .also { created += it }
    }

    private suspend fun TeacherScenario.loaded(vararg tasks: Assignment) {
        list.emit(tasks.toList())
        assertState { !it.isLoading && it.assignments == tasks.toList() }
    }

    @Test
    fun `muestra las tareas del alumno que llegan, con su estado de sincronizacion`() = runTest {
        viewModel().testMvi {
            loaded(task("a"), task("b", pending = true))
        }
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
    fun `borrar pide confirmacion y llama al caso de uso con el alumno y el id`() = runTest {
        coEvery { delete(any(), any()) } returns Result.success(Unit)
        viewModel().testMvi {
            loaded(task("a"), task("b"))
            intent(TeacherAssignmentsIntent.RequestDelete("a"))
            assertState { it.deleteTarget?.id == "a" }
            intent(TeacherAssignmentsIntent.ConfirmDelete)
            assertState { it.isDeleting }
            assertState { it.deleteTarget == null && !it.isDeleting }
        }
        coVerify(exactly = 1) { delete("s1", "a") }
    }

    @Test
    fun `cancelar el borrado no llama al caso de uso`() = runTest {
        viewModel().testMvi {
            loaded(task("a"))
            intent(TeacherAssignmentsIntent.RequestDelete("a"))
            assertState { it.deleteTarget != null }
            intent(TeacherAssignmentsIntent.CancelDelete)
            assertState { it.deleteTarget == null }
        }
        coVerify(exactly = 0) { delete(any(), any()) }
    }

    @Test
    fun `solo se puede borrar una tarea de la lista propia`() = runTest {
        val vm = viewModel()
        vm.testMvi {
            loaded(task("a"))
            intent(TeacherAssignmentsIntent.RequestDelete("ajena"))
            intent(TeacherAssignmentsIntent.ConfirmDelete)
            assertNoEffects()
        }
        assertTrue(vm.state.value.deleteTarget == null)
        coVerify(exactly = 0) { delete(any(), any()) }
    }

    @Test
    fun `doble confirmacion es una sola llamada`() = runTest {
        coEvery { delete(any(), any()) } coAnswers {
            delay(100)
            Result.success(Unit)
        }
        viewModel().testMvi {
            loaded(task("a"))
            intent(TeacherAssignmentsIntent.RequestDelete("a"))
            assertState { it.deleteTarget != null }
            intent(TeacherAssignmentsIntent.ConfirmDelete)
            intent(TeacherAssignmentsIntent.ConfirmDelete)
            assertState { it.isDeleting }
            assertState { !it.isDeleting && it.deleteTarget == null }
        }
        coVerify(exactly = 1) { delete(any(), any()) }
    }

    @Test
    fun `un fallo al borrar cierra el dialogo y muestra el error`() = runTest {
        coEvery { delete(any(), any()) } returns Result.failure(AssignmentFailure.PermissionDenied)
        viewModel().testMvi {
            loaded(task("a"))
            intent(TeacherAssignmentsIntent.RequestDelete("a"))
            assertState { it.deleteTarget != null }
            intent(TeacherAssignmentsIntent.ConfirmDelete)
            assertState { it.isDeleting }
            assertState { it.deleteTarget == null && it.error == TeacherAssignmentsError.PERMISSION_DENIED }
            intent(TeacherAssignmentsIntent.DismissError)
            assertState { it.error == null }
        }
    }

    @Test
    fun `una excepcion del caso de uso se limpia en finally y no mata el procesamiento de intents`() = runTest {
        coEvery { delete("s1", "a") } throws IllegalStateException("boom")
        coEvery { delete("s1", "b") } returns Result.success(Unit)
        viewModel().testMvi {
            loaded(task("a"), task("b"))
            intent(TeacherAssignmentsIntent.RequestDelete("a"))
            assertState { it.deleteTarget != null }
            intent(TeacherAssignmentsIntent.ConfirmDelete)
            assertState { it.isDeleting }
            assertState { !it.isDeleting && it.deleteTarget == null && it.error == TeacherAssignmentsError.UNKNOWN }
            intent(TeacherAssignmentsIntent.RequestDelete("b"))
            assertState { it.deleteTarget?.id == "b" }
            intent(TeacherAssignmentsIntent.ConfirmDelete)
            assertState { it.isDeleting }
            assertState { it.deleteTarget == null && !it.isDeleting }
        }
        coVerify(exactly = 1) { delete("s1", "b") }
    }

    @Test
    fun `una tarea pendiente que desaparece sin haberla borrado se retira con aviso de rechazo`() = runTest {
        viewModel().testMvi {
            loaded(task("a"), task("b", pending = true))
            list.emit(listOf(task("a")))
            assertState { it.assignments == listOf(task("a")) && it.notice == TeacherAssignmentsNotice.REJECTED }
            intent(TeacherAssignmentsIntent.DismissNotice)
            assertState { it.notice == null }
        }
    }

    @Test
    fun `una tarea pendiente que se confirma o que borre yo no genera aviso`() = runTest {
        coEvery { delete(any(), any()) } returns Result.success(Unit)
        viewModel().testMvi {
            loaded(task("a", pending = true), task("b", pending = true))
            list.emit(listOf(task("a"), task("b", pending = true)))
            assertState { it.assignments == listOf(task("a"), task("b", pending = true)) && it.notice == null }
            intent(TeacherAssignmentsIntent.RequestDelete("b"))
            assertState { it.deleteTarget != null }
            intent(TeacherAssignmentsIntent.ConfirmDelete)
            assertState { it.isDeleting }
            assertState { it.deleteTarget == null && !it.isDeleting }
            list.emit(listOf(task("a")))
            assertState { it.assignments == listOf(task("a")) && it.notice == null }
        }
    }

    @Test
    fun `vinculo caido en caliente vacia la vista, marca no disponible y vuelve atras una sola vez`() = runTest {
        val channel = Channel<List<Assignment>>(Channel.UNLIMITED)
        val vm = viewModelWith(channel.receiveAsFlow())
        vm.testMvi {
            channel.send(listOf(task("a")))
            assertState { !it.isLoading && it.assignments.size == 1 }
            // permission-denied: el caso de uso emite vacio y termina el flujo.
            channel.send(emptyList())
            channel.close()
            assertState { it.assignments.isEmpty() && !it.unavailable }
            assertState { it.unavailable && it.assignments.isEmpty() && !it.isLoading }
            assertEffect(TeacherAssignmentsEffect.NavigateBack)
            assertNoEffects()
        }
    }

    @Test
    fun `un fallo del observador marca loadFailed y Retry vuelve a escuchar`() = runTest {
        var attempts = 0
        every { observe("s1") } answers {
            if (attempts++ == 0) flow { throw AssignmentFailure.Unknown } else list
        }
        val vm = TeacherAssignmentsViewModel(SavedStateHandle(mapOf("studentUid" to "s1")), observe, delete)
            .also { created += it }
        vm.testMvi {
            assertState { it.loadFailed && !it.isLoading }
            intent(TeacherAssignmentsIntent.Retry)
            assertState { it.isLoading && !it.loadFailed }
            loaded(task("a"))
        }
    }

    @Test
    fun `sin uid de alumno en la ruta esta no disponible y no observa nada`() = runTest {
        every { observe(any()) } returns list
        val vm = viewModel(uid = null)
        assertTrue(vm.state.value.unavailable)
        assertFalse(vm.state.value.isLoading)
        verify(exactly = 0) { observe(any()) }
    }

    @Test
    fun `toString no incluye titulos ni descripciones`() {
        val state = TeacherAssignmentsState(assignments = listOf(task("a")))
        assertFalse(state.toString().contains("Escalas"))
        assertFalse(state.toString().contains("Do mayor"))
    }
}
