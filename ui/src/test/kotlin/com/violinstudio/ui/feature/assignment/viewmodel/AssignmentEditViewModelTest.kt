package com.violinstudio.ui.feature.assignment.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.assignment.failure.AssignmentFailure
import com.violinstudio.domain.feature.assignment.model.Assignment
import com.violinstudio.domain.feature.assignment.model.AssignmentStatus
import com.violinstudio.domain.feature.assignment.usecase.CreateAssignmentUseCase
import com.violinstudio.domain.feature.assignment.usecase.ObserveStudentAssignmentsUseCase
import com.violinstudio.domain.feature.assignment.usecase.UpdateAssignmentUseCase
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

private typealias EditScenario = MviScenario<AssignmentEditState, AssignmentEditIntent, AssignmentEditEffect>

@ExtendWith(MainDispatcherExtension::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AssignmentEditViewModelTest {
    private val create = mockk<CreateAssignmentUseCase>()
    private val update = mockk<UpdateAssignmentUseCase>()
    private val observe = mockk<ObserveStudentAssignmentsUseCase>()
    private val list = MutableSharedFlow<List<Assignment>>(replay = 1)
    private val due = Instant.parse("2026-12-01T00:00:00Z")
    private val existing = Assignment(
        "a",
        "t1",
        "Escalas",
        "Do mayor",
        due,
        AssignmentStatus.OPEN,
        null,
        Instant.parse("2026-10-01T10:00:00Z"),
        false
    )

    private val created = mutableListOf<AssignmentEditViewModel>()

    @AfterEach
    fun cancelScopes() = created.forEach { it.viewModelScope.cancel() }

    private fun viewModel(assignmentId: String? = null): AssignmentEditViewModel {
        every { observe("s1") } returns list
        val args = buildMap {
            put("studentUid", "s1")
            assignmentId?.let { put("assignmentId", it) }
        }
        return AssignmentEditViewModel(SavedStateHandle(args), create, update, observe).also { created += it }
    }

    private suspend fun EditScenario.fill(title: String = "Escalas", description: String = "Do mayor") {
        intent(AssignmentEditIntent.TitleChanged(title))
        assertState { it.title == title }
        intent(AssignmentEditIntent.DescriptionChanged(description))
        assertState { it.description == description }
    }

    @Test
    fun `crear con datos validos llama al caso de uso con alumno, titulo, descripcion y fecha y cierra`() = runTest {
        coEvery { create(any(), any(), any(), any()) } returns Result.success("id1")
        viewModel().testMvi {
            fill()
            intent(AssignmentEditIntent.DueAtChanged(due))
            assertState { it.dueAt == due }
            intent(AssignmentEditIntent.Save)
            assertState { it.saveAttempted }
            assertState { it.busy }
            assertEffect(AssignmentEditEffect.Saved)
        }
        coVerify(exactly = 1) { create("s1", "Escalas", "Do mayor", due) }
    }

    @Test
    fun `guardar con errores no llama al caso de uso y deja el error inline`() = runTest {
        viewModel().testMvi {
            intent(AssignmentEditIntent.TitleChanged("a".repeat(121)))
            assertState { it.titleError == TitleError.TOO_LONG }
            intent(AssignmentEditIntent.Save)
            assertState { it.saveAttempted }
            assertNoEffects()
        }
        coVerify(exactly = 0) { create(any(), any(), any(), any()) }
    }

    @Test
    fun `guardar vacio marca el titulo como error y no escribe`() = runTest {
        viewModel().testMvi {
            intent(AssignmentEditIntent.Save)
            assertState { it.saveAttempted && it.titleError == TitleError.EMPTY }
            assertNoEffects()
        }
        coVerify(exactly = 0) { create(any(), any(), any(), any()) }
    }

    @Test
    fun `un fallo al guardar muestra el error, no cierra y permite reintentar`() = runTest {
        coEvery { create(any(), any(), any(), any()) } returnsMany listOf(
            Result.failure(AssignmentFailure.PermissionDenied),
            Result.success("id1")
        )
        viewModel().testMvi {
            fill()
            intent(AssignmentEditIntent.Save)
            assertState { it.saveAttempted }
            assertState { it.busy }
            assertState { !it.busy && it.error == AssignmentEditError.PERMISSION_DENIED }
            intent(AssignmentEditIntent.Save)
            assertState { it.busy && it.error == null }
            assertEffect(AssignmentEditEffect.Saved)
        }
    }

    @Test
    fun `una excepcion del caso de uso libera busy en finally y muestra error generico`() = runTest {
        coEvery { create(any(), any(), any(), any()) } throws IllegalStateException("boom")
        viewModel().testMvi {
            fill()
            intent(AssignmentEditIntent.Save)
            assertState { it.saveAttempted }
            assertState { it.busy }
            assertState { !it.busy && it.error == AssignmentEditError.UNKNOWN }
        }
    }

    @Test
    fun `doble toque en guardar con fallo es una sola llamada y luego se puede reintentar`() = runTest {
        coEvery { create(any(), any(), any(), any()) } coAnswers {
            delay(100)
            Result.failure(AssignmentFailure.Unknown)
        }
        viewModel().testMvi {
            fill()
            intent(AssignmentEditIntent.Save)
            intent(AssignmentEditIntent.Save)
            assertState { it.saveAttempted }
            assertState { it.busy }
            assertState { !it.busy && it.error == AssignmentEditError.UNKNOWN }
            runCurrent()
            coVerify(exactly = 1) { create(any(), any(), any(), any()) }
            intent(AssignmentEditIntent.Save)
            assertState { it.busy && it.error == null }
            assertState { !it.busy && it.error == AssignmentEditError.UNKNOWN }
        }
        coVerify(exactly = 2) { create(any(), any(), any(), any()) }
    }

    @Test
    fun `al editar rellena el formulario con la tarea y guarda con update`() = runTest {
        coEvery { update(any(), any(), any(), any(), any()) } returns Result.success(Unit)
        viewModel("a").testMvi {
            list.emit(listOf(existing))
            assertState { it.isEditing && !it.loading && it.title == "Escalas" && it.description == "Do mayor" }
            intent(AssignmentEditIntent.DueAtChanged(null))
            assertState { it.dueAt == null }
            intent(AssignmentEditIntent.Save)
            assertState { it.saveAttempted }
            assertState { it.busy }
            assertEffect(AssignmentEditEffect.Saved)
        }
        coVerify(exactly = 1) { update("s1", "a", "Escalas", "Do mayor", null) }
        coVerify(exactly = 0) { create(any(), any(), any(), any()) }
    }

    @Test
    fun `una re-emision posterior no pisa lo que el profesor esta escribiendo`() = runTest {
        val vm = viewModel("a")
        vm.testMvi {
            list.emit(listOf(existing))
            assertState { it.isEditing && it.title == "Escalas" }
            intent(AssignmentEditIntent.TitleChanged("Arpegios"))
            assertState { it.title == "Arpegios" }
            list.emit(listOf(existing.copy(title = "Otro")))
            runCurrent()
        }
        assertEquals("Arpegios", vm.state.value.title)
    }

    @Test
    fun `una tarea inexistente queda notFound tras el margen y no se puede guardar`() = runTest {
        val vm = viewModel("zzz")
        list.emit(listOf(existing))
        runCurrent()
        assertTrue(vm.state.value.loading)
        advanceTimeBy(SETTLE_MS + 1)
        runCurrent()
        assertTrue(vm.state.value.notFound)
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.canSave)
    }

    @Test
    fun `sin alumno en la ruta no se puede guardar`() = runTest {
        val vm = AssignmentEditViewModel(SavedStateHandle(), create, update, observe).also { created += it }
        vm.testMvi {
            intent(AssignmentEditIntent.TitleChanged("Escalas"))
            assertState { it.title == "Escalas" }
            intent(AssignmentEditIntent.Save)
            assertState { it.saveAttempted }
            assertNoEffects()
        }
        coVerify(exactly = 0) { create(any(), any(), any(), any()) }
    }

    @Test
    fun `al editar, si el flujo termina tras rellenar el vinculo cayo y no se puede guardar`() = runTest {
        val channel = Channel<List<Assignment>>(Channel.UNLIMITED)
        every { observe("s1") } returns channel.receiveAsFlow()
        val handle = SavedStateHandle(mapOf("studentUid" to "s1", "assignmentId" to "a"))
        val vm = AssignmentEditViewModel(handle, create, update, observe).also { created += it }
        vm.testMvi {
            channel.send(listOf(existing))
            assertState { it.isEditing && !it.notFound && it.canSave }
            channel.close()
            assertState { it.notFound && !it.canSave }
        }
    }

    @Test
    fun `al crear, si el flujo del vinculo termina no se puede guardar`() = runTest {
        val channel = Channel<List<Assignment>>(Channel.UNLIMITED)
        every { observe("s1") } returns channel.receiveAsFlow()
        val vm = AssignmentEditViewModel(SavedStateHandle(mapOf("studentUid" to "s1")), create, update, observe)
            .also { created += it }
        vm.testMvi {
            fill()
            channel.close()
            assertState { it.notFound && !it.canSave }
            intent(AssignmentEditIntent.Save)
            assertState { it.saveAttempted }
            assertNoEffects()
        }
        coVerify(exactly = 0) { create(any(), any(), any(), any()) }
    }

    @Test
    fun `al crear sin alumno en la ruta queda no disponible en vez de ignorar el guardado`() = runTest {
        val vm = AssignmentEditViewModel(SavedStateHandle(), create, update, observe).also { created += it }
        assertTrue(vm.state.value.notFound)
        assertFalse(vm.state.value.canSave)
    }
}
