package com.violinstudio.ui.feature.recording.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.violinstudio.domain.feature.recording.model.PlaybackState
import com.violinstudio.domain.feature.recording.model.RecordingSummary
import com.violinstudio.domain.feature.recording.model.Review
import com.violinstudio.domain.feature.recording.model.Verdict
import com.violinstudio.domain.feature.recording.usecase.ObserveMyReviewUseCase
import com.violinstudio.domain.feature.recording.usecase.ObserveStudentRecordingsUseCase
import com.violinstudio.domain.feature.recording.usecase.RecordingPlaybackUseCase
import com.violinstudio.domain.feature.recording.usecase.SaveReviewUseCase
import com.violinstudio.ui.commons.testing.MainDispatcherExtension
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainDispatcherExtension::class)
class StudentRecordingsViewModelTest {
    private val r0 = RecordingSummary("r0", 1, 1_000, ready = true, isDemo = false, objectPath = "p")
    private val list = MutableStateFlow(listOf(r0))
    private val observe = mockk<ObserveStudentRecordingsUseCase> { every { this@mockk.invoke("s1") } returns list }
    private val myReview = mockk<ObserveMyReviewUseCase> {
        every { this@mockk.invoke("s1", "r0") } returns flowOf(null)
    }
    private val save = mockk<SaveReviewUseCase> {
        coEvery { this@mockk.invoke(any(), any(), any(), any()) } returns Result.success(Unit)
    }
    private val playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    private val playback = mockk<RecordingPlaybackUseCase>(relaxUnitFun = true) {
        every { state } returns playbackState
    }

    private fun TestScope.viewModel(uid: String? = "s1"): StudentRecordingsViewModel {
        val handle =
            SavedStateHandle(uid?.let { mapOf(StudentRecordingsViewModel.STUDENT_UID_KEY to it) } ?: emptyMap())
        return StudentRecordingsViewModel(handle, observe, myReview, save, playback).also { runCurrent() }
    }

    private val StudentRecordingsViewModel.now get() = state.value

    @Test
    fun `carga las grabaciones del alumno de la ruta`() = runTest {
        val vm = viewModel()
        assertFalse(vm.now.isLoading)
        assertEquals(listOf(r0), vm.now.recordings)
    }

    @Test
    fun `sin alumno en la ruta o si las reglas deniegan muestra el fallo y reintenta`() = runTest {
        assertTrue(viewModel(uid = null).now.loadFailed)
        every { observe("s1") } returns flow { throw IllegalStateException("denied") }
        val vm = viewModel()
        assertTrue(vm.now.loadFailed)
        every { observe("s1") } returns list
        vm.onIntent(StudentRecordingsIntent.Retry)
        runCurrent()
        assertFalse(vm.now.loadFailed)
        assertEquals(listOf(r0), vm.now.recordings)
        vm.onIntent(StudentRecordingsIntent.Retry)
        runCurrent()
        verify(exactly = 2) { observe("s1") }
    }

    @Test
    fun `escuchar, parar y salir`() = runTest {
        val vm = viewModel()
        vm.onIntent(StudentRecordingsIntent.Play("zz"))
        vm.onIntent(StudentRecordingsIntent.Play("r0"))
        runCurrent()
        coVerify(exactly = 1) { playback.play(r0) }
        playbackState.value = PlaybackState.Failed("r0")
        runCurrent()
        assertEquals(PlaybackState.Failed("r0"), vm.now.playback)
        vm.onIntent(StudentRecordingsIntent.Stop)
        vm.onIntent(StudentRecordingsIntent.Leave)
        runCurrent()
        verify(exactly = 2) { playback.stop() }
    }

    @Test
    fun `valorar por primera vez guarda y cierra el editor`() = runTest {
        val vm = viewModel()
        vm.onIntent(StudentRecordingsIntent.OpenReview("zz"))
        runCurrent()
        assertNull(vm.now.editor)
        vm.onIntent(StudentRecordingsIntent.OpenReview("r0"))
        runCurrent()
        assertFalse(vm.now.editor!!.loading)
        vm.onIntent(StudentRecordingsIntent.SaveReview)
        runCurrent()
        coVerify(exactly = 0) { save(any(), any(), any(), any()) }
        vm.onIntent(StudentRecordingsIntent.SetVerdict(Verdict.APPROVED))
        vm.onIntent(StudentRecordingsIntent.SetText("Muy bien"))
        vm.onIntent(StudentRecordingsIntent.SaveReview)
        runCurrent()
        coVerify { save("s1", "r0", Verdict.APPROVED, "Muy bien") }
        assertNull(vm.now.editor)
        assertEquals(StudentRecordingsMessage.SAVED, vm.now.message)
        vm.onIntent(StudentRecordingsIntent.DismissMessage)
        runCurrent()
        assertNull(vm.now.message)
    }

    @Test
    fun `editar una valoracion parte de la guardada y un fallo deja el editor abierto`() = runTest {
        every { myReview("s1", "r0") } returns flowOf(Review(Verdict.NEEDS_WORK, "Arco", 1L))
        val result = CompletableDeferred<Result<Unit>>()
        coEvery { save(any(), any(), any(), any()) } coAnswers { result.await() }
        val vm = viewModel()
        vm.onIntent(StudentRecordingsIntent.OpenReview("r0"))
        runCurrent()
        assertTrue(vm.now.editor!!.existing)
        assertEquals("Arco", vm.now.editor!!.text)
        vm.onIntent(StudentRecordingsIntent.SaveReview)
        runCurrent()
        assertTrue(vm.now.editor!!.saving)
        // Guardar no retiene otros intents.
        vm.onIntent(StudentRecordingsIntent.Stop)
        runCurrent()
        verify { playback.stop() }
        result.complete(Result.failure(IllegalStateException("x")))
        runCurrent()
        assertFalse(vm.now.editor!!.saving)
        assertEquals(StudentRecordingsMessage.SAVE_FAILED, vm.now.message)
        vm.onIntent(StudentRecordingsIntent.CloseReview)
        runCurrent()
        assertNull(vm.now.editor)
    }

    @Test
    fun `si no se puede leer la valoracion se cierra el editor y se avisa`() = runTest {
        every { myReview("s1", "r0") } returns flow { throw IllegalStateException("x") }
        val vm = viewModel()
        vm.onIntent(StudentRecordingsIntent.OpenReview("r0"))
        runCurrent()
        assertNull(vm.now.editor)
        assertEquals(StudentRecordingsMessage.REVIEW_LOAD_FAILED, vm.now.message)
    }
}
