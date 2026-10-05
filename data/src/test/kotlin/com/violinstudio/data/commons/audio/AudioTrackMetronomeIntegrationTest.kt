package com.violinstudio.data.commons.audio

import com.violinstudio.domain.feature.metronome.BeatScheduler
import com.violinstudio.domain.feature.metronome.MetronomeGenerator
import com.violinstudio.domain.feature.metronome.model.BeatTick
import com.violinstudio.domain.feature.metronome.model.Tempo
import com.violinstudio.domain.feature.metronome.model.TimeSignature
import com.violinstudio.domain.feature.metronome.usecase.RunMetronomeUseCase
import com.violinstudio.domain.feature.tuner.audio.PcmFormat
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [AudioTrackOutput] + [MetronomeGenerator] con una pista fake: los clics caen en los indices del buffer escrito. */
class AudioTrackMetronomeIntegrationTest {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "audio-out-metronome-test") }

    @AfterEach
    fun tearDown() {
        executor.shutdownNow()
    }

    private class RecordingTrack(private val lag: Int = 0) : PcmTrack {
        val samples: MutableList<Float> = Collections.synchronizedList(mutableListOf())
        val released = CountDownLatch(1)

        override fun play() = Unit

        override fun write(buffer: FloatArray, offset: Int, size: Int): Int {
            Thread.sleep(1)
            samples.addAll(buffer.slice(offset until offset + size))
            return size
        }

        override fun playbackHeadPosition(): Int = maxOf(0, samples.size - lag)

        override fun pause() = Unit

        override fun flush() = Unit

        override fun release() = released.countDown()
    }

    @Test
    fun `los clics caen en los indices de muestra de cada tiempo`() {
        val track = RecordingTrack()
        val output = AudioTrackOutput({ track }, executor.asCoroutineDispatcher())
        val scheduler = BeatScheduler(Tempo(120), TimeSignature.FOUR_FOUR)
        val perBeat = PcmFormat.SAMPLE_RATE / 2
        val target = perBeat * 2L + 2_000
        runBlocking { withTimeout(10_000) { output.play(MetronomeGenerator(scheduler)).first { it >= target } } }
        assertTrue(track.released.await(5, TimeUnit.SECONDS))

        val written = track.samples.toFloatArray()
        fun peak(from: Int, until: Int) = (from until until).maxOf { abs(written[it]) }
        for (beat in 0L..2L) {
            val start = scheduler.sampleOf(beat).toInt()
            assertTrue(peak(start, start + 1_000) > 0.3f, "clic en el tiempo $beat")
        }
        assertEquals(0f, peak(3_000, perBeat - 1), "silencio entre clics")
        assertTrue(peak(0, 1_000) > peak(perBeat, perBeat + 1_000), "acento mayor que tiempo normal")
    }

    @Test
    fun `el caso de uso con setTempo en vivo da ticks consecutivos y clics en los indices escritos`() {
        val track = RecordingTrack(lag = 2 * PcmFormat.BLOCK_SIZE)
        val output = AudioTrackOutput({ track }, executor.asCoroutineDispatcher())
        val session = RunMetronomeUseCase(output)(Tempo(240), TimeSignature.FOUR_FOUR)
        val ticks = Collections.synchronizedList(mutableListOf<BeatTick>())
        val changed = AtomicBoolean()
        runBlocking {
            withTimeout(10_000) {
                session.ticks.first {
                    ticks += it
                    if (ticks.size == 2 && changed.compareAndSet(false, true)) session.setTempo(Tempo(120))
                    ticks.size >= 5
                }
            }
        }
        assertTrue(track.released.await(5, TimeUnit.SECONDS))
        assertEquals(ticks.indices.map { it.toLong() }, ticks.map { it.beat })
        assertEquals(listOf(true, false, false, false, true), ticks.map { it.accent })
        val written = track.samples.toFloatArray()
        // Los dos primeros tiempos a 240 BPM (11 025 muestras) suenan en sus indices.
        for (start in listOf(0, 11_025)) {
            assertTrue((start until start + 1_000).maxOf { abs(written[it]) } > 0.3f, "clic en $start")
        }
    }
}
