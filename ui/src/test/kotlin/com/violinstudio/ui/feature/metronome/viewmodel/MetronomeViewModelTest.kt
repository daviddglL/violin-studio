package com.violinstudio.ui.feature.metronome.viewmodel

import com.violinstudio.domain.feature.metronome.MetronomeGenerator
import com.violinstudio.domain.feature.metronome.model.Tempo
import com.violinstudio.domain.feature.metronome.model.TimeSignature
import com.violinstudio.domain.feature.metronome.usecase.RunMetronomeUseCase
import com.violinstudio.domain.feature.tuner.audio.AudioOutput
import com.violinstudio.domain.feature.tuner.audio.PcmGenerator
import com.violinstudio.domain.feature.tuner.failure.TunerFailure
import com.violinstudio.ui.commons.testing.MainDispatcherExtension
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

private class FakeOutput : AudioOutput {
    val played = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    var active = 0
    var maxActive = 0
    var plays = 0
    var failure: Throwable? = null
    var stuck = false
    lateinit var generator: MetronomeGenerator

    override fun play(generator: PcmGenerator): Flow<Long> = flow {
        plays++
        maxActive = maxOf(maxActive, ++active)
        this@FakeOutput.generator = generator as MetronomeGenerator
        try {
            failure?.let { throw it }
            played.collect { emit(it) }
        } finally {
            active--
            if (stuck) withContext(NonCancellable) { delay(10_000) }
        }
    }

    /** Aplica el cambio de tempo pendiente como haria el hilo de audio al escribir el siguiente bloque. */
    fun writeBlock() = generator.fill(FloatArray(1024), 0)
}

private class TestClock(var now: Long = 0) : Clock() {
    override fun getZone(): ZoneId = ZoneId.of("UTC")

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = Instant.ofEpochMilli(now)

    override fun millis(): Long = now
}

@ExtendWith(MainDispatcherExtension::class)
@OptIn(ExperimentalCoroutinesApi::class)
class MetronomeViewModelTest {
    private val output = FakeOutput()
    private val clock = TestClock()

    private fun vm() = MetronomeViewModel(RunMetronomeUseCase(output), clock)

    private fun TestScope.playing(): MetronomeViewModel {
        val vm = vm()
        vm.onIntent(MetronomeIntent.Toggle)
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `Toggle inicia y para, y el tiempo sale de los frames reproducidos`() = runTest {
        val vm = playing()
        assertTrue(vm.state.value.isPlaying)
        output.played.emit(1)
        advanceUntilIdle()
        assertEquals(0, vm.state.value.tick?.position)
        assertTrue(vm.state.value.tick!!.accent)
        output.played.emit(26_461) // a 100 BPM el segundo tiempo cae en la muestra 26 460
        advanceUntilIdle()
        assertEquals(1, vm.state.value.tick?.position)
        vm.onIntent(MetronomeIntent.Toggle)
        advanceUntilIdle()
        assertEquals(0, output.active)
        assertFalse(vm.state.value.isPlaying)
        assertNull(vm.state.value.tick)
    }

    @Test
    fun `cambiar el bpm sonando se aplica en vivo sin reiniciar`() = runTest {
        val vm = playing()
        vm.onIntent(MetronomeIntent.SetBpm(150))
        vm.onIntent(MetronomeIntent.Increment)
        advanceUntilIdle()
        output.writeBlock()
        assertEquals(151, vm.state.value.tempo.bpm)
        assertEquals(Tempo(151), output.generator.scheduler.tempo)
        assertEquals(1, output.plays)
    }

    @Test
    fun `el bpm se acota en los extremos`() = runTest {
        val vm = vm()
        vm.onIntent(MetronomeIntent.SetBpm(30))
        vm.onIntent(MetronomeIntent.Decrement)
        advanceUntilIdle()
        assertEquals(30, vm.state.value.tempo.bpm)
        vm.onIntent(MetronomeIntent.SetBpm(250))
        vm.onIntent(MetronomeIntent.Increment)
        advanceUntilIdle()
        assertEquals(250, vm.state.value.tempo.bpm)
    }

    @Test
    fun `cambiar de compas sonando abre una sesion nueva con una sola salida`() = runTest {
        val vm = playing()
        vm.onIntent(MetronomeIntent.SetSignature(TimeSignature.SIX_EIGHT))
        advanceUntilIdle()
        assertEquals(2, output.plays)
        assertEquals(1, output.maxActive)
        assertEquals(1, output.active)
        assertEquals(TimeSignature.SIX_EIGHT, output.generator.scheduler.signature)
    }

    @Test
    fun `cambiar de compas parado no suena`() = runTest {
        val vm = vm()
        vm.onIntent(MetronomeIntent.SetSignature(TimeSignature.THREE_FOUR))
        advanceUntilIdle()
        assertEquals(0, output.plays)
        assertEquals(TimeSignature.THREE_FOUR, vm.state.value.signature)
    }

    @Test
    fun `Stop detiene y Resume reanuda solo si sonaba`() = runTest {
        val vm = playing()
        vm.onIntent(MetronomeIntent.Stop)
        vm.onIntent(MetronomeIntent.Stop) // ON_STOP y onDispose seguidos (rotacion)
        advanceUntilIdle()
        assertEquals(0, output.active)
        vm.onIntent(MetronomeIntent.Resume)
        advanceUntilIdle()
        assertEquals(1, output.active)
        vm.onIntent(MetronomeIntent.Resume) // un segundo ON_START (p. ej. al rotar) no abre otra salida
        advanceUntilIdle()
        assertEquals(1, output.maxActive)
        assertEquals(2, output.plays)
    }

    @Test
    fun `Resume sin haber sonado no arranca`() = runTest {
        val vm = vm()
        vm.onIntent(MetronomeIntent.Stop)
        vm.onIntent(MetronomeIntent.Resume)
        advanceUntilIdle()
        assertEquals(0, output.plays)
    }

    @Test
    fun `un fallo de la salida se muestra y Toggle reintenta`() = runTest {
        output.failure = TunerFailure.AudioOutputUnavailable
        val vm = playing()
        assertFalse(vm.state.value.isPlaying)
        assertEquals(MetronomeError.OUTPUT_UNAVAILABLE, vm.state.value.error)
        output.failure = null
        vm.onIntent(MetronomeIntent.Toggle)
        advanceUntilIdle()
        assertTrue(vm.state.value.isPlaying)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `Tap solo cambia el bpm desde el segundo toque`() = runTest {
        val vm = vm()
        vm.onIntent(MetronomeIntent.Tap)
        advanceUntilIdle()
        assertEquals(Tempo.DEFAULT, vm.state.value.tempo)
        clock.now = 500
        vm.onIntent(MetronomeIntent.Tap)
        advanceUntilIdle()
        assertEquals(120, vm.state.value.tempo.bpm)
    }

    @Test
    fun `una salida que no termina de cancelarse no bloquea las intenciones`() = runTest {
        output.stuck = true
        val vm = playing()
        vm.onIntent(MetronomeIntent.Toggle)
        advanceTimeBy(600)
        assertFalse(vm.state.value.isPlaying)
        vm.onIntent(MetronomeIntent.SetBpm(90))
        advanceTimeBy(600)
        assertEquals(90, vm.state.value.tempo.bpm)
    }
}
