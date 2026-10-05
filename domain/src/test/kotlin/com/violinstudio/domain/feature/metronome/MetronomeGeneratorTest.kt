package com.violinstudio.domain.feature.metronome

import com.violinstudio.domain.feature.metronome.model.Tempo
import com.violinstudio.domain.feature.metronome.model.TimeSignature
import com.violinstudio.domain.testing.FakeAudioOutput
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MetronomeGeneratorTest {
    private fun scheduler(bpm: Int = 120, origin: Long = 0) = BeatScheduler(Tempo(bpm), TimeSignature.FOUR_FOUR, origin)

    private fun render(generator: MetronomeGenerator, total: Int, block: Int): FloatArray {
        val out = FloatArray(total)
        var start = 0
        while (start < total) {
            val buffer = FloatArray(minOf(block, total - start))
            generator.fill(buffer, start.toLong())
            buffer.copyInto(out, start)
            start += buffer.size
        }
        return out
    }

    @Test
    fun `un clic partido entre bloques es identico al renderizado en un solo buffer`() {
        val whole = render(MetronomeGenerator(scheduler(250, origin = 100)), 40_000, 40_000)
        assertTrue(whole.any { it != 0f })
        listOf(1_024, 1_000, 333, 1).forEach { block ->
            val split = render(MetronomeGenerator(scheduler(250, origin = 100)), 40_000, block)
            assertEquals(whole.toList(), split.toList(), "block=$block")
        }
    }

    @Test
    fun `los clics estan en los indices del planificador con acento en el 1 y silencio entre ellos`() = runTest {
        val output = FakeAudioOutput()
        output.play(MetronomeGenerator(scheduler())).take(180).toList()
        val written = output.written
        val s = scheduler()
        (0L until 8L).forEach { k ->
            val at = s.sampleOf(k).toInt()
            val click = if (s.isAccent(k)) ClickSynth.accent else ClickSynth.normal
            assertEquals(click.toList(), written.slice(at until at + click.size), "k=$k")
            assertTrue(written.slice(at + click.size until s.sampleOf(k + 1).toInt()).all { it == 0f })
        }
    }

    @Test
    fun `finish cierra con la cola del clic en curso y no inicia clics nuevos`() {
        val whole = render(MetronomeGenerator(scheduler()), 3_000, 3_000)
        val tail = FloatArray(1_000)
        assertTrue(MetronomeGenerator(scheduler()).finish(tail, 1_000))
        assertEquals(whole.slice(1_000 until 2_000).take(323), tail.toList().take(323))
        assertTrue(tail.drop(323).all { it == 0f })
        assertEquals(false, MetronomeGenerator(scheduler(origin = 5_000)).finish(FloatArray(1_000), 0))
    }
}
