package com.violinstudio.ui.feature.intonation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.intonation.model.IntonationExercises
import com.violinstudio.domain.feature.intonation.model.IntonationTracker
import com.violinstudio.domain.feature.intonation.usecase.ObserveIntonationBestUseCase
import com.violinstudio.domain.feature.intonation.usecase.SaveIntonationScoreUseCase
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.tuner.failure.TunerFailure
import com.violinstudio.domain.feature.tuner.model.Note
import com.violinstudio.domain.feature.tuner.model.ReferencePitch
import com.violinstudio.domain.feature.tuner.model.TunerConfig
import com.violinstudio.domain.feature.tuner.model.TunerReading
import com.violinstudio.domain.feature.tuner.model.TuningTarget
import com.violinstudio.domain.feature.tuner.usecase.ObservePitchUseCase
import com.violinstudio.domain.feature.tuner.usecase.ObserveTunerConfigUseCase
import com.violinstudio.ui.commons.testing.MainDispatcherExtension
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.math.pow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MainDispatcherExtension::class)
@OptIn(ExperimentalCoroutinesApi::class)
class IntonationViewModelTest {
    private val exercise = IntonationExercises.byId("a_string_pattern")!!
    private val ref = ReferencePitch.DEFAULT
    private var readings = MutableSharedFlow<TunerReading>(extraBufferCapacity = 64)
    private var failure: Throwable? = null
    private var captures = 0
    private val observePitch = mockk<ObservePitchUseCase> {
        every { this@mockk(Instrument.VIOLIN, any(), null) } answers {
            captures++
            val error = failure
            if (error != null) flow { throw error } else readings
        }
    }
    private val observeConfig =
        mockk<ObserveTunerConfigUseCase> { every { this@mockk() } returns flowOf(TunerConfig()) }
    private val best = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val observeBest = mockk<ObserveIntonationBestUseCase> { every { this@mockk() } returns best }
    private val saveScore = mockk<SaveIntonationScoreUseCase> {
        coEvery { this@mockk(any(), any()) } returns Result.success(Unit)
    }
    private val created = mutableListOf<IntonationViewModel>()

    @AfterEach
    fun cancelScopes() = created.forEach { it.viewModelScope.cancel() }

    private fun viewModel(id: String? = exercise.id) = IntonationViewModel(
        SavedStateHandle(buildMap { id?.let { put(IntonationViewModel.EXERCISE_ID_KEY, it) } }),
        observePitch,
        observeConfig,
        observeBest,
        saveScore
    ).also { created += it }

    private fun pitch(note: Note, cents: Double) =
        TunerReading.Pitch(note.frequency(ref) * 2.0.pow(cents / 1200.0), TuningTarget.Chromatic(note), cents, 1.0)

    private suspend fun TestScope.hold(vm: IntonationViewModel, cents: Double) {
        val note = vm.state.value.expected!!
        repeat(IntonationTracker.HOLD_FRAMES) { readings.emit(pitch(note, cents)) }
        advanceUntilIdle()
    }

    @Test
    fun `con un id desconocido no hay ejercicio y no escucha`() = runTest {
        val vm = viewModel("nope")
        vm.onIntent(IntonationIntent.Start(granted = true))
        advanceUntilIdle()
        assertNull(vm.state.value.exercise)
        assertEquals(0, captures)
    }

    @Test
    fun `sin permiso lo pide; denegado o bloqueado lo dice y bloqueado ya no lo pide`() = runTest {
        val vm = viewModel()
        val effects = mutableListOf<IntonationEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        vm.onIntent(IntonationIntent.Start(granted = false))
        advanceUntilIdle()
        assertEquals(listOf<IntonationEffect>(IntonationEffect.RequestMicPermission), effects)
        vm.onIntent(IntonationIntent.PermissionResult(granted = false, rationale = true))
        advanceUntilIdle()
        assertEquals(IntonationMic.DENIED, vm.state.value.mic)
        vm.onIntent(IntonationIntent.PermissionResult(granted = false, rationale = false))
        vm.onIntent(IntonationIntent.Start(granted = false))
        advanceUntilIdle()
        assertEquals(IntonationMic.BLOCKED, vm.state.value.mic)
        assertEquals(1, effects.size)
        vm.onIntent(IntonationIntent.PermissionResult(granted = true, rationale = false))
        advanceUntilIdle()
        assertEquals(IntonationMic.OK, vm.state.value.mic)
        assertTrue(vm.state.value.isListening)
    }

    @Test
    fun `un intento completo puntua, guarda la puntuacion y suelta el micro`() = runTest {
        val vm = viewModel()
        vm.onIntent(IntonationIntent.Start(granted = true))
        advanceUntilIdle()
        assertTrue(vm.state.value.isListening)
        assertEquals(Note(69), vm.state.value.expected)
        readings.emit(pitch(Note(69), 4.0))
        advanceUntilIdle()
        assertEquals(4.0, vm.state.value.cents!!, 1e-6)
        repeat(exercise.notes.size) { i -> hold(vm, if (i == 1) 30.0 else 0.0) }
        val state = vm.state.value
        assertFalse(state.isListening)
        assertNull(state.expected)
        val summary = state.summary!!
        assertEquals(exercise.notes.size - 1, summary.inTune)
        assertEquals(93, summary.score) // (6 + 0.5) / 7
        assertTrue(state.isNewBest)
        coVerify(exactly = 1) { saveScore(exercise.id, 93) }
    }

    @Test
    fun `saltarlo todo da cero, sin notas tocadas, y no es mejor que la previa`() = runTest {
        best.value = mapOf(exercise.id to 80)
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(80, vm.state.value.best)
        repeat(exercise.notes.size) { vm.onIntent(IntonationIntent.Skip) }
        advanceUntilIdle()
        val summary = vm.state.value.summary!!
        assertEquals(0, summary.score)
        assertEquals(0, summary.played)
        assertFalse(vm.state.value.isNewBest)
        coVerify(exactly = 1) { saveScore(exercise.id, 0) }
    }

    @Test
    fun `pausar conserva lo hecho y continuar sigue por la misma nota`() = runTest {
        val vm = viewModel()
        vm.onIntent(IntonationIntent.Start(granted = true))
        advanceUntilIdle()
        hold(vm, 0.0)
        vm.onIntent(IntonationIntent.Pause)
        advanceUntilIdle()
        assertFalse(vm.state.value.isListening)
        assertEquals(1, vm.state.value.index)
        vm.onIntent(IntonationIntent.Start(granted = true))
        advanceUntilIdle()
        assertEquals(2, captures)
        assertEquals(Note(71), vm.state.value.expected)
        hold(vm, 0.0)
        assertEquals(2, vm.state.value.index)
    }

    @Test
    fun `un fallo del micro se muestra y repetir empieza de cero`() = runTest {
        failure = TunerFailure.MicBusy
        val vm = viewModel()
        vm.onIntent(IntonationIntent.Skip)
        vm.onIntent(IntonationIntent.Start(granted = true))
        advanceUntilIdle()
        assertEquals(IntonationError.MIC_BUSY, vm.state.value.error)
        assertFalse(vm.state.value.isListening)
        vm.onIntent(IntonationIntent.Restart)
        advanceUntilIdle()
        assertEquals(0, vm.state.value.index)
        assertEquals(Note(69), vm.state.value.expected)
        assertNull(vm.state.value.error)
    }
}
