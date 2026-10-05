package com.violinstudio.domain.feature.metronome.usecase

import com.violinstudio.domain.feature.metronome.model.BeatTick
import com.violinstudio.domain.feature.metronome.model.Tempo
import com.violinstudio.domain.feature.metronome.model.TimeSignature
import com.violinstudio.domain.feature.tuner.audio.AudioOutput
import com.violinstudio.domain.feature.tuner.audio.PcmFormat
import com.violinstudio.domain.feature.tuner.audio.PcmGenerator
import com.violinstudio.domain.feature.tuner.failure.TunerFailure
import com.violinstudio.domain.testing.FakeAudioOutput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
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
        assertConsecutiveAndAccented(ticks, TimeSignature.FOUR_FOUR)
        assertTrue(ticks.last().beat > 3)
    }

    @Test
    fun `con la reproduccion retrasada dos bloques el cambio de tempo no salta ni retrocede`() = runTest {
        val lagging = object : AudioOutput {
            override fun play(generator: PcmGenerator): Flow<Long> =
                output.play(generator).map { it - 2L * PcmFormat.BLOCK_SIZE }
        }
        val session = RunMetronomeUseCase(lagging)(Tempo(250), TimeSignature.THREE_FOUR)
        val ticks = mutableListOf<BeatTick>()
        val job = collectTicks(session.ticks, ticks)
        advanceTimeBy(20L * 40)
        session.setTempo(Tempo(60))
        advanceTimeBy(20L * 200)
        session.setTempo(Tempo(250))
        advanceTimeBy(20L * 200)
        job.cancel()
        advanceUntilIdle()
        assertTrue(ticks.size > 5)
        assertConsecutiveAndAccented(ticks, TimeSignature.THREE_FOUR)
    }

    @Test
    fun `volver a colectar tras cancelar empieza limpio en el tiempo 0 con el clic en la muestra 0`() = runTest {
        val session = run(Tempo(120), TimeSignature.FOUR_FOUR)
        val first = mutableListOf<BeatTick>()
        collectTicks(session.ticks, first).also {
            advanceTimeBy(20L * 60)
            session.setTempo(Tempo(240))
            advanceTimeBy(20L * 60)
            it.cancel()
        }
        advanceUntilIdle()
        val offset = output.written.size
        val second = mutableListOf<BeatTick>()
        collectTicks(session.ticks, second).also {
            advanceTimeBy(20L * 20)
            it.cancel()
        }
        advanceUntilIdle()
        assertEquals(BeatTick(0, 0, true), second.first())
        assertEquals(Tempo(240), session.tempo)
        val head = output.written.copyOfRange(offset, offset + 1_000)
        assertTrue(head.maxOf { kotlin.math.abs(it) } > 0.3f, "el clic empieza en la muestra 0")
        assertConsecutiveAndAccented(second, TimeSignature.FOUR_FOUR)
    }

    @Test
    fun `setTempo antes de colectar vale para la primera reproduccion`() = runTest {
        val session = run(Tempo(60), TimeSignature.FOUR_FOUR)
        session.setTempo(Tempo(250))
        val ticks = mutableListOf<BeatTick>()
        val job = collectTicks(session.ticks, ticks)
        advanceTimeBy(20L * 60)
        job.cancel()
        advanceUntilIdle()
        // 250 BPM = 10 584 muestras por tiempo: en 60 bloques (61 440) suenan varios tiempos; a 60 BPM solo 1.
        assertTrue(ticks.last().beat >= 4)
    }

    private fun assertConsecutiveAndAccented(ticks: List<BeatTick>, signature: TimeSignature) {
        assertEquals(0L, ticks.first().beat)
        assertEquals(ticks.indices.map { it.toLong() }, ticks.map { it.beat })
        assertEquals(ticks.map { it.beat % signature.beats == 0L }, ticks.map { it.accent })
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
