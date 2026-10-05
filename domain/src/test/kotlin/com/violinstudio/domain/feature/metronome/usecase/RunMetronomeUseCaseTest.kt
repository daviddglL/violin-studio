package com.violinstudio.domain.feature.metronome.usecase

import com.violinstudio.domain.feature.metronome.model.BeatTick
import com.violinstudio.domain.feature.metronome.model.Tempo
import com.violinstudio.domain.feature.metronome.model.TimeSignature
import com.violinstudio.domain.feature.tuner.failure.TunerFailure
import com.violinstudio.domain.testing.FakeAudioOutput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RunMetronomeUseCaseTest {
    private val output = FakeAudioOutput()
    private val run = RunMetronomeUseCase(output)

    private fun TestScope.collectTicks(ticks: Flow<BeatTick>, into: MutableList<BeatTick>) =
        launch { ticks.collect { into += it } }

    @Test
    fun `los tiempos siguen los frames reproducidos y no se repiten`() = runTest {
        val ticks = mutableListOf<BeatTick>()
        val job = collectTicks(run(Tempo(120), TimeSignature.FOUR_FOUR).ticks, ticks)
        // 120 BPM = un tiempo cada 22 050 muestras = ~21,5 bloques de 1024 (20 ms virtuales cada uno).
        advanceTimeBy(20L * 50)
        job.cancel()
        advanceUntilIdle()
        assertEquals(listOf(0L, 1L, 2L), ticks.map { it.beat })
    }

    @Test
    fun `el indicador va por lo reproducido, el primer tiempo aparece con la primera posicion`() = runTest {
        val ticks = mutableListOf<BeatTick>()
        val job = collectTicks(run(Tempo(120), TimeSignature.FOUR_FOUR).ticks, ticks)
        advanceTimeBy(1)
        job.cancel()
        advanceUntilIdle()
        assertEquals(listOf(BeatTick(beat = 0, position = 0, accent = true)), ticks)
    }

    @Test
    fun `el acento cae en el primer tiempo de cada compas`() = runTest {
        val ticks = mutableListOf<BeatTick>()
        val job = collectTicks(run(Tempo(250), TimeSignature.THREE_FOUR).ticks, ticks)
        advanceTimeBy(20L * 200)
        job.cancel()
        advanceUntilIdle()
        assertTrue(ticks.size >= 7)
        assertEquals(ticks.map { it.beat % 3 == 0L }, ticks.map { it.accent })
        assertEquals(ticks.map { (it.beat % 3).toInt() }, ticks.map { it.position })
    }

    @Test
    fun `un cambio de tempo en vivo llega al generador sin reiniciar el flujo`() = runTest {
        val session = run(Tempo(120), TimeSignature.FOUR_FOUR)
        val ticks = mutableListOf<BeatTick>()
        val job = collectTicks(session.ticks, ticks)
        advanceTimeBy(20L * 10)
        session.setTempo(Tempo(240))
        advanceTimeBy(20L * 100)
        job.cancel()
        advanceUntilIdle()
        assertEquals(Tempo(240), session.tempo)
        assertEquals(1, output.maxActive)
        assertEquals(ticks.map { it.beat }.distinct(), ticks.map { it.beat })
        // 120 BPM durante ~10 bloques y luego 240: mas tiempos que a 120 constantes.
        assertTrue(ticks.last().beat > 3)
    }

    @Test
    fun `cancelar libera la salida una sola vez`() = runTest {
        val job = collectTicks(run(Tempo(100), TimeSignature.TWO_FOUR).ticks, mutableListOf())
        advanceTimeBy(100)
        job.cancel()
        advanceUntilIdle()
        assertEquals(1, output.releases)
        assertEquals(0, output.active)
    }

    @Test
    fun `un fallo de la salida llega tipado al colector`() = runTest {
        val failing = RunMetronomeUseCase(FakeAudioOutput(failure = TunerFailure.AudioOutputUnavailable))
        var caught: Throwable? = null
        failing(Tempo(100), TimeSignature.FOUR_FOUR).ticks.catch { caught = it }.collect { }
        assertSame(TunerFailure.AudioOutputUnavailable, caught)
    }
}
