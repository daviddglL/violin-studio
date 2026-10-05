package com.violinstudio.data.commons.audio

import com.violinstudio.domain.feature.tuner.audio.PcmFormat
import com.violinstudio.domain.feature.tuner.audio.PcmGenerator
import com.violinstudio.domain.feature.tuner.failure.TunerFailure
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AudioTrackOutputTest {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "audio-out-test") }
    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    @AfterEach
    fun tearDown() {
        executor.shutdownNow()
    }

    /** Escribir "cuesta" 1 ms (como un write bloqueante); la cabeza de reproduccion va por detras de lo escrito. */
    private class FakeTrack(private val writeResult: (call: Int) -> Int? = { null }) : PcmTrack {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val blocks: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        val released = CountDownLatch(1)
        private var frames = 0L

        override fun play() {
            calls += "play"
        }

        override fun write(buffer: FloatArray, size: Int): Int {
            Thread.sleep(1)
            blocks += size
            val result = writeResult(blocks.size) ?: size
            if (result > 0) frames += result
            return result
        }

        override fun playbackHeadPosition(): Int = (frames / 2).toInt()

        override fun pause() {
            calls += "pause"
        }

        override fun flush() {
            calls += "flush"
        }

        override fun release() {
            calls += "release"
            released.countDown()
        }
    }

    private fun output(track: FakeTrack, onCreate: () -> Unit = {}) = AudioTrackOutput(
        { size ->
            onCreate()
            assertEquals(PcmFormat.BLOCK_SIZE, size)
            track
        },
        dispatcher
    )

    private val silence = PcmGenerator { _, _ -> }

    @Test
    fun `escribe bloques de 1024 con indices de muestra contiguos y emite la posicion reproducida`() = runBlocking {
        val starts = Collections.synchronizedList(mutableListOf<Long>())
        val track = FakeTrack()
        val positions = withTimeout(5_000) {
            output(track).play { _, start -> starts += start }.take(3).toList()
        }
        assertTrue(positions.zipWithNext().all { (a, b) -> b >= a } && positions.last() > 0)
        assertEquals(List(starts.size) { it * PcmFormat.BLOCK_SIZE.toLong() }, starts.toList())
        assertEquals("play", track.calls.first())
    }

    @Test
    fun `al cancelar hace pause, flush y release una sola vez`() = runBlocking {
        val track = FakeTrack()
        val job = launch { output(track).play(silence).first() }
        withTimeout(5_000) { job.join() }
        assertTrue(track.released.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("play", "pause", "flush", "release"), track.calls.toList())
    }

    @Test
    fun `un fallo de escritura libera y llega como salida no disponible`() = runBlocking {
        val track = FakeTrack { call -> if (call == 2) -3 else null }
        val result = runCatching { withTimeout(5_000) { output(track).play(silence).collect { } } }
        assertSame(TunerFailure.AudioOutputUnavailable, result.exceptionOrNull())
        assertEquals(1, track.calls.count { it == "release" })
    }

    @Test
    fun `si la pista no se puede crear llega como salida no disponible`() = runBlocking {
        val out = AudioTrackOutput({ error("no track") }, dispatcher)
        val result = runCatching { withTimeout(5_000) { out.play(silence).collect { } } }
        assertSame(TunerFailure.AudioOutputUnavailable, result.exceptionOrNull())
    }

    @Test
    fun `la posicion se corrige cuando el contador de 32 bits desborda`() {
        val position = PlaybackPosition()
        assertEquals(100L, position.update(100))
        assertEquals(Int.MAX_VALUE + 1L, position.update(Int.MIN_VALUE))
        assertEquals(1L shl 32, position.update(0))
        assertEquals((1L shl 32) + 5, position.update(5))
    }
}
