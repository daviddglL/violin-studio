package com.violinstudio.ui.feature.link.viewmodel

import com.violinstudio.domain.feature.link.failure.LinkFailure
import com.violinstudio.domain.feature.link.model.TeacherLink
import com.violinstudio.domain.feature.link.usecase.ObserveMyTeacherUseCase
import com.violinstudio.domain.feature.link.usecase.UnlinkUseCase
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.ui.commons.testing.MainDispatcherExtension
import com.violinstudio.ui.commons.testing.MviScenario
import com.violinstudio.ui.commons.testing.testMvi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

private typealias TeacherScenario = MviScenario<MyTeacherState, MyTeacherIntent, MyTeacherEffect>

@ExtendWith(MainDispatcherExtension::class)
class MyTeacherViewModelTest {
    private val observe = mockk<ObserveMyTeacherUseCase>()
    private val unlink = mockk<UnlinkUseCase>()
    private val links = MutableSharedFlow<TeacherLink?>(replay = 1)
    private val link =
        TeacherLink("t1", "s1", "Marta", "Ana", Instrument.VIOLIN, 1, Instant.parse("2026-10-01T10:00:00Z"))

    private fun viewModel(): MyTeacherViewModel {
        every { observe() } returns links
        return MyTeacherViewModel(observe, unlink)
    }

    private fun unlinkAnswers(result: Result<Unit>) {
        coEvery { unlink(any(), any()) } coAnswers {
            delay(100)
            result
        }
    }

    private suspend fun TeacherScenario.withTeacher() {
        links.emit(link)
        assertState { !it.isLoading && it.teacher == link }
    }

    @Test
    fun `muestra al profesor cuando llega el vinculo`() = runTest {
        viewModel().testMvi { withTeacher() }
    }

    @Test
    fun `la primera emision vacia no es sin profesor hasta que se asienta`() = runTest {
        viewModel().testMvi {
            links.emit(null)
            // No hay un estado intermedio "sin profesor": el siguiente estado ya es el asentado.
            assertState { !it.isLoading && it.teacher == null }
        }
    }

    @Test
    fun `la emision vacia inicial mantiene la carga durante el margen de asentamiento`() = runTest {
        val vm = viewModel()
        links.emit(null)
        runCurrent()
        advanceTimeBy(MyTeacherViewModel.SETTLE_MS - 1)
        assertTrue(vm.state.value.isLoading)
        advanceTimeBy(2)
        runCurrent()
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun `un vinculo que llega tras la primera emision vacia gana sin parpadeo`() = runTest {
        viewModel().testMvi {
            links.emit(null)
            links.emit(link)
            assertState { !it.isLoading && it.teacher == link }
        }
    }

    @Test
    fun `si el vinculo cae despues de verse se vacia al instante`() = runTest {
        viewModel().testMvi {
            withTeacher()
            links.emit(null)
            assertState { !it.isLoading && it.teacher == null }
        }
    }

    @Test
    fun `un fallo del flujo deja de cargar sin romper`() = runTest {
        every { observe() } returns flow { throw LinkFailure.Offline }
        MyTeacherViewModel(observe, unlink).testMvi {
            assertState { !it.isLoading && it.teacher == null }
        }
    }

    @Test
    fun `cancelar la confirmacion no llama a desvincular`() = runTest {
        viewModel().testMvi {
            withTeacher()
            intent(MyTeacherIntent.RequestUnlink)
            assertState { it.confirmingUnlink }
            intent(MyTeacherIntent.CancelUnlink)
            assertState { !it.confirmingUnlink }
        }
        coVerify(exactly = 0) { unlink(any(), any()) }
    }

    @Test
    fun `confirmar desvincula con las dos partes del vinculo y cierra el dialogo`() = runTest {
        unlinkAnswers(Result.success(Unit))
        viewModel().testMvi {
            withTeacher()
            intent(MyTeacherIntent.RequestUnlink)
            assertState { it.confirmingUnlink }
            intent(MyTeacherIntent.ConfirmUnlink)
            assertState { it.isUnlinking }
            assertState { !it.isUnlinking && !it.confirmingUnlink && it.error == null }
        }
        coVerify(exactly = 1) { unlink("t1", "s1") }
    }

    @Test
    fun `confirmar sin pedirlo antes no llama`() = runTest {
        viewModel().testMvi {
            withTeacher()
            intent(MyTeacherIntent.ConfirmUnlink)
        }
        coVerify(exactly = 0) { unlink(any(), any()) }
    }

    @Test
    fun `una doble confirmacion llama una sola vez`() = runTest {
        unlinkAnswers(Result.success(Unit))
        viewModel().testMvi {
            withTeacher()
            intent(MyTeacherIntent.RequestUnlink)
            assertState { it.confirmingUnlink }
            intent(MyTeacherIntent.ConfirmUnlink)
            intent(MyTeacherIntent.ConfirmUnlink)
            assertState { it.isUnlinking }
            assertState { !it.isUnlinking }
        }
        coVerify(exactly = 1) { unlink(any(), any()) }
    }

    @Test
    fun `un fallo se muestra y permite reintentar`() = runTest {
        unlinkAnswers(Result.failure(LinkFailure.Offline))
        viewModel().testMvi {
            withTeacher()
            intent(MyTeacherIntent.RequestUnlink)
            assertState { it.confirmingUnlink }
            intent(MyTeacherIntent.ConfirmUnlink)
            assertState { it.isUnlinking }
            assertState { !it.isUnlinking && !it.confirmingUnlink && it.error == MyTeacherError.Offline }
            intent(MyTeacherIntent.RequestUnlink)
            assertState { it.confirmingUnlink && it.error == null }
        }
    }

    @Test
    fun `fallos transitorios y desconocidos se distinguen`() = runTest {
        val cases = listOf(
            LinkFailure.TemporarilyUnavailable to MyTeacherError.TemporarilyUnavailable,
            LinkFailure.Unknown to MyTeacherError.Unknown
        )
        for ((failure, expected) in cases) {
            unlinkAnswers(Result.failure(failure))
            viewModel().testMvi {
                withTeacher()
                intent(MyTeacherIntent.RequestUnlink)
                assertState { it.confirmingUnlink }
                intent(MyTeacherIntent.ConfirmUnlink)
                assertState { it.isUnlinking }
                assertState { it.error == expected }
            }
        }
    }
}
