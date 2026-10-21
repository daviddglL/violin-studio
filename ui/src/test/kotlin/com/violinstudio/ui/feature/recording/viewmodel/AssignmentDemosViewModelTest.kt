package com.violinstudio.ui.feature.recording.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.violinstudio.domain.feature.recording.model.PlaybackState
import com.violinstudio.domain.feature.recording.model.RecordingSummary
import com.violinstudio.domain.feature.recording.usecase.ObserveAssignmentDemosUseCase
import com.violinstudio.domain.feature.recording.usecase.RecordingPlaybackUseCase
import com.violinstudio.ui.commons.testing.MainDispatcherExtension
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainDispatcherExtension::class)
class AssignmentDemosViewModelTest {
    private val d0 = RecordingSummary("d0", 1, 1_000, ready = true, isDemo = true, objectPath = "p")
    private val list = MutableStateFlow(listOf(d0))
    private val observe = mockk<ObserveAssignmentDemosUseCase> { every { this@mockk.invoke("t1", "a1") } returns list }
    private val playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    private val playback = mockk<RecordingPlaybackUseCase>(relaxUnitFun = true) {
        every { state } returns playbackState
    }

    private fun TestScope.viewModel(teacher: String? = "t1", assignment: String? = "a1"): AssignmentDemosViewModel {
        val args = buildMap {
            teacher?.let { put(AssignmentDemosViewModel.TEACHER_UID_KEY, it) }
            assignment?.let { put(AssignmentDemosViewModel.ASSIGNMENT_ID_KEY, it) }
        }
        return AssignmentDemosViewModel(SavedStateHandle(args), observe, playback).also { runCurrent() }
    }

    private val AssignmentDemosViewModel.now get() = state.value

    @Test
    fun `carga las demos del profesor y la tarea de la ruta`() = runTest {
        val vm = viewModel()
        assertFalse(vm.now.isLoading)
        assertEquals(listOf(d0), vm.now.demos)
        list.value = emptyList()
        runCurrent()
        assertTrue(vm.now.demos.isEmpty())
    }

    @Test
    fun `sin argumentos o si las reglas deniegan muestra el fallo y reintenta`() = runTest {
        assertTrue(viewModel(teacher = null).now.loadFailed)
        assertTrue(viewModel(assignment = null).now.loadFailed)
        every { observe("t1", "a1") } returns flow { throw IllegalStateException("denied") }
        val vm = viewModel()
        assertTrue(vm.now.loadFailed)
        every { observe("t1", "a1") } returns list
        vm.onIntent(AssignmentDemosIntent.Retry)
        runCurrent()
        assertFalse(vm.now.loadFailed)
        assertEquals(listOf(d0), vm.now.demos)
        // Con la lista cargada, reintentar no vuelve a suscribirse.
        vm.onIntent(AssignmentDemosIntent.Retry)
        runCurrent()
        verify(exactly = 2) { observe("t1", "a1") }
    }

    @Test
    fun `escuchar, parar y salir`() = runTest {
        val vm = viewModel()
        vm.onIntent(AssignmentDemosIntent.Play("zz"))
        vm.onIntent(AssignmentDemosIntent.Play("d0"))
        runCurrent()
        coVerify(exactly = 1) { playback.play(d0) }
        playbackState.value = PlaybackState.Playing("d0")
        runCurrent()
        assertEquals(PlaybackState.Playing("d0"), vm.now.playback)
        vm.onIntent(AssignmentDemosIntent.Stop)
        vm.onIntent(AssignmentDemosIntent.Leave)
        runCurrent()
        verify(exactly = 2) { playback.stop() }
    }
}
