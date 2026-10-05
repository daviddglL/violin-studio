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
    private fun scheduler(bpm: Int = 120, origin: Long = 0, signature: TimeSignature = TimeSignature.FOUR_FOUR) =
        BeatScheduler(Tempo(bpm), signature, origin)

    private fun render(generator: MetronomeGenerator, total: Int, block: Int, offset: Long = 0): FloatArray {
        val out = FloatArray(total)
        var start = 0
        while (start < total) {
            val buffer = FloatArray(minOf(block, total - start))
            generator.fill(buffer, offset + start)
            buffer.copyInto(out, start)
            start += buffer.size
        }
        return out
    }

    /** Buffer de [total] muestras con los clics dados por (indice, acento) y silencio en el resto. */
    private fun expectedClicks(total: Int, vararg clicks: Pair<Int, Boolean>): List<Float> {
        val out = FloatArray(total)
        clicks.forEach { (at, accent) ->
            val click = if (accent) ClickSynth.accent else ClickSynth.normal
            click.copyInto(out, at)
        }
        return out.toList()
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

    @Test
    fun `un cambio de tempo a mitad de clic no corta el clic en curso`() {
        val reference = render(MetronomeGenerator(scheduler(100)), 3_000, 3_000)
        val generator = MetronomeGenerator(scheduler(100))
        val out = FloatArray(3_000)
        listOf(0, 1_000, 2_000).forEach { start ->
            if (start == 1_000) generator.requestTempo(Tempo(200))
            val buffer = FloatArray(1_000).also { generator.fill(it, start.toLong()) }
            buffer.copyInto(out, start)
        }
        assertEquals(reference.slice(0 until ClickSynth.accent.size), out.slice(0 until ClickSynth.accent.size))
        assertEquals(0f, out[ClickSynth.accent.size + 10])
    }

    @Test
    fun `un cambio de tempo en el limite de bloque no duplica ni pierde clics`() {
        val generator = MetronomeGenerator(scheduler(120))
        val out = FloatArray(80_000)
        var start = 0
        while (start < out.size) {
            if (start == 22_050) generator.requestTempo(Tempo(100))
            val buffer = FloatArray(minOf(1_050, out.size - start))
            generator.fill(buffer, start.toLong())
            buffer.copyInto(out, start)
            start += buffer.size
        }
        // El clic 1 conserva su posicion (22050); despues el intervalo es el de 100 BPM (26460).
        val expected = expectedClicks(80_000, 0 to true, 22_050 to false, 48_510 to false, 74_970 to false)
        assertEquals(expected, out.toList())
    }

    @Test
    fun `requestTempo desde otro hilo no corrompe el estado`() {
        val generator = MetronomeGenerator(scheduler(120))
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val requester = Thread {
            var bpm = 30
            while (running.get()) {
                generator.requestTempo(Tempo(bpm))
                bpm = if (bpm >= 250) 30 else bpm + 7
            }
        }
        requester.start()
        val out = render(generator, 1_024 * 2_000, 1_024)
        running.set(false)
        requester.join()
        assertTrue(out.all { it.isFinite() && kotlin.math.abs(it) <= 1f })
        assertTrue(out.any { it != 0f })
    }

    @Test
    fun `finish con un clic que sigue mas alla del bloque lo cierra con una rampa a cero`() {
        val tail = FloatArray(1_024)
        assertTrue(MetronomeGenerator(scheduler()).finish(tail, 100))
        val raw = ClickSynth.accent
        assertEquals(raw.slice(100 until 100 + 1_024 - 88), tail.slice(0 until 1_024 - 88))
        assertEquals(0f, kotlin.math.abs(tail.last()))
        assertTrue((1_024 - 88 until 1_024).all { kotlin.math.abs(tail[it]) <= kotlin.math.abs(raw[100 + it]) })
    }

    @Test
    fun `con un startSample enorme el resultado es el mismo que cerca de cero`() {
        val big = 3_000_000_000_000L
        val near = render(MetronomeGenerator(scheduler(250, origin = 100)), 30_000, 1_000)
        val far = render(MetronomeGenerator(scheduler(250, origin = big + 100)), 30_000, 1_000, offset = big)
        assertEquals(near.toList(), far.toList())
    }

    @Test
    fun `en 6-8 solo el primer tiempo de cada compas suena acentuado`() {
        val s = scheduler(120, signature = TimeSignature.SIX_EIGHT)
        val out = render(MetronomeGenerator(s), 7 * 22_050, 1_024)
        val expected = (0 until 7).map { s.sampleOf(it.toLong()).toInt() to (it % 6 == 0) }.toTypedArray()
        assertEquals(expectedClicks(7 * 22_050, *expected), out.toList())
    }
}
