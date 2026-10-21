package com.violinstudio.ui.feature.teacher.viewmodel

import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.link.failure.LinkFailure
import com.violinstudio.domain.feature.link.model.TeacherLink
import com.violinstudio.domain.feature.link.usecase.ObserveMyStudentsUseCase
import com.violinstudio.domain.feature.link.usecase.UnlinkUseCase
import com.violinstudio.domain.feature.profile.model.Instrument
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

private typealias StudentsScenario = MviScenario<StudentsState, StudentsIntent, StudentsEffect>

@ExtendWith(MainDispatcherExtension::class)
@OptIn(ExperimentalCoroutinesApi::class)
class StudentsViewModelTest {
    private val observe = mockk<ObserveMyStudentsUseCase>()
    private val unlink = mockk<UnlinkUseCase>()
    private val students = MutableSharedFlow<List<TeacherLink>>(replay = 1)
    private val ana = student("s1", "Ana")
    private val leo = student("s2", "Leo")

    private val created = mutableListOf<StudentsViewModel>()

    // Leccion 2: ninguna corrutina viva entre tests (el observador es de larga vida).
    @AfterEach
    fun cancelScopes() = created.forEach { it.viewModelScope.cancel() }

    private fun student(uid: String, name: String) =
        TeacherLink("t1", uid, "Marta", name, Instrument.VIOLIN, 1, Instant.parse("2026-10-01T10:00:00Z"))

    private fun viewModel(): StudentsViewModel {
        every { observe() } returns students
        return create()
    }

    private fun create() = StudentsViewModel(observe, unlink).also { created += it }

    private fun unlinkAnswers(result: Result<Unit>) {
        coEvery { unlink(any(), any()) } coAnswers {
            delay(100)
            result
        }
    }

    private suspend fun StudentsScenario.withStudents() {
        students.emit(listOf(ana, leo))
        assertState { !it.isLoading && it.students == listOf(ana, leo) }
    }

    @Test
    fun `muestra los alumnos que llegan`() = runTest {
        viewModel().testMvi { withStudents() }
    }

    @Test
    fun `la lista vacia inicial mantiene la carga durante el margen de asentamiento`() = runTest {
        val vm = viewModel()
        students.emit(emptyList())
        runCurrent()
        advanceTimeBy(SETTLE_MS - 1)
        assertTrue(vm.state.value.isLoading)
        advanceTimeBy(2)
        runCurrent()
        assertFalse(vm.state.value.isLoading)
        assertTrue(vm.state.value.students.isEmpty())
    }

    @Test
    fun `alumnos tras una primera lista vacia ganan sin parpadeo`() = runTest {
        viewModel().testMvi {
            students.emit(emptyList())
            students.emit(listOf(ana))
            assertState { !it.isLoading && it.students == listOf(ana) }
        }
    }

    @Test
    fun `un alumno desvinculado en caliente desaparece de la lista al instante`() = runTest {
        viewModel().testMvi {
            withStudents()
            students.emit(listOf(leo))
            assertState { it.students == listOf(leo) }
            students.emit(emptyList())
            assertState { !it.isLoading && it.students.isEmpty() && !it.loadFailed }
        }
    }

    @Test
    fun `permission-denied vacia la lista sin error`() = runTest {
        every { observe() } returns flow { throw LinkFailure.PermissionDenied }
        create().testMvi {
            assertState { !it.isLoading && it.students.isEmpty() && !it.loadFailed }
        }
    }

    @Test
    fun `otro fallo del flujo deja de cargar y ofrece reintentar`() = runTest {
        every { observe() } returns flow { throw LinkFailure.Offline }
        create().testMvi {
            assertState { !it.isLoading && it.students.isEmpty() && it.loadFailed }
        }
    }

    @Test
    fun `reintentar vuelve a observar y recupera la lista`() = runTest {
        var attempt = 0
        every { observe() } answers { if (attempt++ == 0) flow { throw LinkFailure.Unknown } else students }
        create().testMvi {
            assertState { it.loadFailed && !it.isLoading }
            intent(StudentsIntent.Retry)
            assertState { it.isLoading && !it.loadFailed }
            students.emit(listOf(ana))
            assertState { !it.isLoading && !it.loadFailed && it.students == listOf(ana) }
        }
    }

    @Test
    fun `cancelar la confirmacion no llama a desvincular`() = runTest {
        viewModel().testMvi {
            withStudents()
            intent(StudentsIntent.RequestUnlink(ana))
            assertState { it.unlinkTarget == ana }
            intent(StudentsIntent.CancelUnlink)
            assertState { it.unlinkTarget == null }
        }
        coVerify(exactly = 0) { unlink(any(), any()) }
    }

    @Test
    fun `confirmar desvincula al alumno elegido y cierra el dialogo`() = runTest {
        unlinkAnswers(Result.success(Unit))
        viewModel().testMvi {
            withStudents()
            intent(StudentsIntent.RequestUnlink(leo))
            assertState { it.unlinkTarget == leo }
            intent(StudentsIntent.ConfirmUnlink)
            assertState { it.isUnlinking }
            assertState { !it.isUnlinking && it.unlinkTarget == null && it.error == null }
        }
        coVerify(exactly = 1) { unlink("t1", "s2") }
    }

    @Test
    fun `confirmar sin pedirlo antes no llama`() = runTest {
        viewModel().testMvi {
            withStudents()
            intent(StudentsIntent.ConfirmUnlink)
        }
        coVerify(exactly = 0) { unlink(any(), any()) }
    }

    @Test
    fun `una doble confirmacion llama una sola vez`() = runTest {
        unlinkAnswers(Result.success(Unit))
        viewModel().testMvi {
            withStudents()
            intent(StudentsIntent.RequestUnlink(ana))
            assertState { it.unlinkTarget == ana }
            intent(StudentsIntent.ConfirmUnlink)
            intent(StudentsIntent.ConfirmUnlink)
            assertState { it.isUnlinking }
            assertState { !it.isUnlinking }
        }
        coVerify(exactly = 1) { unlink(any(), any()) }
    }

    @Test
    fun `un fallo se muestra y permite volver a pedirlo`() = runTest {
        unlinkAnswers(Result.failure(LinkFailure.Offline))
        viewModel().testMvi {
            withStudents()
            intent(StudentsIntent.RequestUnlink(ana))
            assertState { it.unlinkTarget == ana }
            intent(StudentsIntent.ConfirmUnlink)
            assertState { it.isUnlinking }
            assertState { !it.isUnlinking && it.unlinkTarget == null && it.error == StudentsError.Offline }
            intent(StudentsIntent.RequestUnlink(ana))
            assertState { it.unlinkTarget == ana && it.error == null }
        }
    }

    @Test
    fun `fallos transitorios y desconocidos se distinguen`() = runTest {
        val cases = listOf(
            LinkFailure.TemporarilyUnavailable to StudentsError.TemporarilyUnavailable,
            LinkFailure.Unknown to StudentsError.Unknown
        )
        for ((failure, expected) in cases) {
            unlinkAnswers(Result.failure(failure))
            viewModel().testMvi {
                withStudents()
                intent(StudentsIntent.RequestUnlink(ana))
                assertState { it.unlinkTarget == ana }
                intent(StudentsIntent.ConfirmUnlink)
                assertState { it.isUnlinking }
                assertState { it.error == expected }
            }
        }
    }

    @Test
    fun `el estado no revela nombres`() = runTest {
        val vm = viewModel()
        students.emit(listOf(ana))
        runCurrent()
        assertFalse(vm.state.value.toString().contains("Ana"))
    }
}
