package com.violinstudio.ui.feature.video.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.violinstudio.domain.feature.recording.failure.RecordingFailure
import com.violinstudio.domain.feature.recording.model.VideoSpec
import com.violinstudio.domain.feature.recording.usecase.DiscardVideoUseCase
import com.violinstudio.domain.feature.recording.usecase.FinishVideoUseCase
import com.violinstudio.domain.feature.recording.usecase.StartVideoUseCase
import com.violinstudio.ui.commons.testing.MainDispatcherExtension
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
class VideoCaptureViewModelTest {
    private val spec = VideoSpec("v1", "u1", "/x/v1.raw.mp4", "/x/v1.mp4", 60_000, 59_500, 0)
    private val start = mockk<StartVideoUseCase> { coEvery { this@mockk.invoke() } returns Result.success(spec) }
    private val finish = mockk<FinishVideoUseCase> {
        coEvery { this@mockk.invoke(any(), any(), any()) } returns Result.success(Unit)
    }
    private val discard = mockk<DiscardVideoUseCase>(relaxUnitFun = true)
    private val effects = mutableListOf<VideoCaptureEffect>()

    private fun TestScope.viewModel(args: Map<String, String> = emptyMap()): VideoCaptureViewModel =
        VideoCaptureViewModel(start, finish, discard, SavedStateHandle(args)).also { vm ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
            runCurrent()
        }

    private val VideoCaptureViewModel.now: VideoCaptureState get() = state.value

    private fun TestScope.send(vm: VideoCaptureViewModel, intent: VideoCaptureIntent) {
        vm.onIntent(intent)
        runCurrent()
    }

    @Test
    fun `grabar pide la toma al dominio, abre la camara en la ruta del original y cuenta el tiempo`() = runTest {
        val vm = viewModel()
        send(vm, VideoCaptureIntent.Start(granted = true, rationale = false))
        assertEquals(VideoPhase.RECORDING, vm.now.phase)
        assertEquals(60_000L, vm.now.maxDurationMs)
        assertEquals(listOf<VideoCaptureEffect>(VideoCaptureEffect.BeginCapture(spec.rawPath, 60_000)), effects)
        advanceTimeBy(1_100)
        assertEquals(1_000L, vm.now.elapsedMs)
        send(vm, VideoCaptureIntent.Leave)
    }

    @Test
    fun `parar cierra la camara, difumina al cerrarse el fichero y vuelve a la lista`() = runTest {
        val vm = viewModel()
        send(vm, VideoCaptureIntent.Start(granted = true, rationale = false))
        send(vm, VideoCaptureIntent.Stop)
        assertEquals(VideoPhase.STOPPING, vm.now.phase)
        assertEquals(VideoCaptureEffect.EndCapture, effects.last())
        send(vm, VideoCaptureIntent.CaptureFinalized(spec.rawPath, ok = true))
        coVerify(exactly = 1) { finish(spec, null, null) }
        assertEquals(VideoPhase.IDLE, vm.now.phase)
        assertEquals(VideoCaptureEffect.Saved, effects.last())
        coVerify(exactly = 0) { discard(any()) }
    }

    @Test
    fun `al llegar al limite para solo`() = runTest {
        val vm = viewModel()
        send(vm, VideoCaptureIntent.Start(granted = true, rationale = false))
        advanceTimeBy(59_600)
        runCurrent()
        assertEquals(VideoPhase.STOPPING, vm.now.phase)
        assertEquals(VideoCaptureEffect.EndCapture, effects.last())
    }

    @Test
    fun `la camara que corta por el tope de duracion tambien vale como parada`() = runTest {
        val vm = viewModel()
        send(vm, VideoCaptureIntent.Start(granted = true, rationale = false))
        send(vm, VideoCaptureIntent.CaptureFinalized(spec.rawPath, ok = true))
        coVerify(exactly = 1) { finish(spec, null, null) }
        assertEquals(VideoCaptureEffect.Saved, effects.last())
    }

    @Test
    fun `en modo demo la toma va al alumno y la tarea de la ruta`() = runTest {
        val vm = viewModel(
            mapOf(VideoCaptureViewModel.STUDENT_UID_KEY to "s1", VideoCaptureViewModel.ASSIGNMENT_ID_KEY to "a1")
        )
        assertTrue(vm.now.demo)
        send(vm, VideoCaptureIntent.Start(granted = true, rationale = false))
        send(vm, VideoCaptureIntent.Stop)
        send(vm, VideoCaptureIntent.CaptureFinalized(spec.rawPath, ok = true))
        coVerify(exactly = 1) { finish(spec, "a1", "s1") }
    }

    @Test
    fun `un fallo de camara borra la toma y avisa`() = runTest {
        val vm = viewModel()
        send(vm, VideoCaptureIntent.Start(granted = true, rationale = false))
        send(vm, VideoCaptureIntent.CaptureFinalized(spec.rawPath, ok = false))
        coVerify(exactly = 1) { discard(spec) }
        coVerify(exactly = 0) { finish(any(), any(), any()) }
        assertEquals(VideoPhase.IDLE, vm.now.phase)
        assertEquals(VideoMessage.CAMERA_FAILED, vm.now.message)
    }

    @Test
    fun `los fallos al difuminar o guardar tienen mensaje y se puede volver a grabar`() = runTest {
        val vm = viewModel()
        listOf(
            RecordingFailure.TooShort to VideoMessage.TOO_SHORT,
            RecordingFailure.TooLarge to VideoMessage.TOO_LARGE,
            RecordingFailure.BlurFailed to VideoMessage.BLUR_FAILED,
            IllegalStateException("x") to VideoMessage.SAVE_FAILED
        ).forEach { (failure, message) ->
            coEvery { finish(any(), any(), any()) } returns Result.failure(failure)
            send(vm, VideoCaptureIntent.Start(granted = true, rationale = false))
            send(vm, VideoCaptureIntent.Stop)
            send(vm, VideoCaptureIntent.CaptureFinalized(spec.rawPath, ok = true))
            assertEquals(message, vm.now.message)
            assertEquals(VideoPhase.IDLE, vm.now.phase)
        }
        assertFalse(VideoCaptureEffect.Saved in effects)
    }

    @Test
    fun `los fallos al empezar no abren la camara`() = runTest {
        val vm = viewModel()
        listOf(
            RecordingFailure.QuotaReached to VideoMessage.QUOTA_REACHED,
            RecordingFailure.Unavailable to VideoMessage.OFFLINE,
            RecordingFailure.Disabled to VideoMessage.NOT_AVAILABLE,
            RecordingFailure.ConsentRequired to VideoMessage.NOT_AVAILABLE
        ).forEach { (failure, message) ->
            coEvery { start() } returns Result.failure(failure)
            send(vm, VideoCaptureIntent.Start(granted = true, rationale = false))
            assertEquals(message, vm.now.message)
            assertEquals(VideoPhase.IDLE, vm.now.phase)
        }
        assertTrue(effects.none { it is VideoCaptureEffect.BeginCapture })
    }

    @Test
    fun `salir grabando cancela la camara y borra la toma, y su cierre tardio la vuelve a borrar`() = runTest {
        val vm = viewModel()
        send(vm, VideoCaptureIntent.Start(granted = true, rationale = false))
        send(vm, VideoCaptureIntent.Leave)
        assertEquals(VideoCaptureEffect.CancelCapture, effects.last())
        assertEquals(VideoPhase.IDLE, vm.now.phase)
        coVerify(exactly = 1) { discard(spec) }
        send(vm, VideoCaptureIntent.CaptureFinalized(spec.rawPath, ok = false))
        coVerify(exactly = 2) { discard(spec) }
        assertNull(vm.now.message)
        coVerify(exactly = 0) { finish(any(), any(), any()) }
    }

    @Test
    fun `el cierre tardio de una toma descartada no cierra la siguiente`() = runTest {
        val next = spec.copy(id = "v2", rawPath = "/x/v2.raw.mp4", outputPath = "/x/v2.mp4")
        val vm = viewModel()
        send(vm, VideoCaptureIntent.Start(granted = true, rationale = false))
        send(vm, VideoCaptureIntent.Leave)
        coEvery { start() } returns Result.success(next)
        send(vm, VideoCaptureIntent.Start(granted = true, rationale = false))
        send(vm, VideoCaptureIntent.CaptureFinalized(spec.rawPath, ok = false))
        assertEquals(VideoPhase.RECORDING, vm.now.phase)
        coVerify(exactly = 0) { discard(next) }
        send(vm, VideoCaptureIntent.Leave)
    }

    @Test
    fun `salir sin toma no descarta nada`() = runTest {
        val vm = viewModel()
        send(vm, VideoCaptureIntent.Leave)
        assertTrue(effects.isEmpty())
        coVerify(exactly = 0) { discard(any()) }
    }

    @Test
    fun `sin permisos se piden, se explican o se manda a Ajustes`() = runTest {
        val vm = viewModel()
        send(vm, VideoCaptureIntent.Start(granted = false, rationale = false))
        assertEquals(listOf<VideoCaptureEffect>(VideoCaptureEffect.RequestPermissions), effects)
        send(vm, VideoCaptureIntent.Start(granted = false, rationale = true))
        assertEquals(CameraPrompt.RATIONALE, vm.now.prompt)
        send(vm, VideoCaptureIntent.ConfirmRationale)
        assertNull(vm.now.prompt)
        assertEquals(VideoCaptureEffect.RequestPermissions, effects.last())
        send(vm, VideoCaptureIntent.PermissionResult(granted = false, rationale = false))
        assertEquals(CameraPrompt.BLOCKED, vm.now.prompt)
        send(vm, VideoCaptureIntent.OpenAppSettings)
        assertEquals(VideoCaptureEffect.OpenAppSettings, effects.last())
        send(vm, VideoCaptureIntent.DismissPrompt)
        assertNull(vm.now.prompt)
        send(vm, VideoCaptureIntent.PermissionResult(granted = true, rationale = false))
        assertEquals(VideoPhase.RECORDING, vm.now.phase)
        send(vm, VideoCaptureIntent.Leave)
    }

    @Test
    fun `los estados, intents y efectos no exponen la ruta del original`() {
        val texts = listOf(
            VideoCaptureEffect.BeginCapture(spec.rawPath, 60_000).toString(),
            VideoCaptureIntent.CaptureFinalized(spec.rawPath, ok = true).toString(),
            VideoCaptureState(phase = VideoPhase.RECORDING).toString()
        )
        texts.forEach { assertFalse("/x/" in it, it) }
    }
}
