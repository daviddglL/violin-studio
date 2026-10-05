package com.violinstudio.data.commons.audio

import com.violinstudio.domain.feature.tuner.audio.PcmFormat
import com.violinstudio.domain.feature.tuner.audio.PcmGenerator
import com.violinstudio.domain.feature.tuner.failure.TunerFailure
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AudioTrackOutputTest {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "audio-out-test").apply { isDaemon = true } }
    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    @AfterEach
    fun tearDown() {
        executor.shutdownNow()
    }

    /**
     * Pista fake. Cabeza de reproduccion = lo escrito. Tras [stallAfter] escrituras, `write` se atasca en un latch
     * que SOLO abren `pause()`/`release()` (como un `AudioTrack` real bloqueado) y entonces devuelve error.
     */
    private class FakeTrack(
        private val stallAfter: Int = Int.MAX_VALUE,
        private val maxPerWrite: Int = Int.MAX_VALUE,
        private val zeroWrites: Boolean = false,
        private val errorAt: Int = 0,
        private val playFailure: Throwable? = null
    ) : PcmTrack {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val samples: MutableList<Float> = Collections.synchronizedList(mutableListOf())
        val released = CountDownLatch(1)
        val stalled = CountDownLatch(1)
        private val unblock = CountDownLatch(1)
        private val writes = AtomicInteger()
        private var frames = 0L

        override fun play() {
            calls += "play"
            playFailure?.let { throw it }
        }

        override fun write(buffer: FloatArray, offset: Int, size: Int): Int {
            if (zeroWrites) return 0
            if (writes.get() + 1 == errorAt) return ERROR_DEAD_OBJECT
            if (writes.incrementAndGet() > stallAfter) {
                stalled.countDown()
                // Acotado: si nadie lo abre, el test falla rapido en vez de colgar la CI.
                unblock.await(10, TimeUnit.SECONDS)
                return ERROR_DEAD_OBJECT
            }
            // Un write real bloquea hasta tener hueco: sin pausa el bucle giraria a toda velocidad.
            Thread.sleep(1)
            val n = minOf(size, maxPerWrite)
            samples.addAll(buffer.slice(offset until offset + n))
            frames += n
            calls += "write"
            return n
        }

        override fun playbackHeadPosition(): Int = frames.toInt()

        override fun pause() {
            calls += "pause"
            unblock.countDown()
        }

        override fun flush() {
            calls += "flush"
        }

        override fun release() {
            calls += "release"
            unblock.countDown()
            released.countDown()
        }

        fun count(call: String) = synchronized(calls) { calls.count { it == call } }
    }

    private fun output(vararg tracks: FakeTrack): AudioTrackOutput {
        val queue = tracks.toMutableList()
        return AudioTrackOutput(
            { size ->
                assertEquals(PcmFormat.BLOCK_SIZE, size)
                queue.removeAt(0)
            },
            dispatcher
        )
    }

    /** Espera acotada FUERA de `withTimeout`: un hijo no cancelable atascado no puede ignorarla. */
    private suspend fun assertJoined(job: Job) {
        val done = CountDownLatch(1)
        job.invokeOnCompletion { done.countDown() }
        assertTrue(withContext(Dispatchers.IO) { done.await(5, TimeUnit.SECONDS) }, "el job no termino a tiempo")
    }

    private val silence = PcmGenerator { _, _ -> }
    private val index = PcmGenerator { buffer, start -> for (i in buffer.indices) buffer[i] = (start + i).toFloat() }

    @Test
    fun `pre-escribe dos bloques antes de arrancar y escribe indices contiguos`() = runBlocking {
        val starts = Collections.synchronizedList(mutableListOf<Long>())
        val track = FakeTrack()
        val positions = withTimeout(5_000) {
            output(track).play { _, start -> starts += start }.take(2).toList()
        }
        assertTrue(positions.zipWithNext().all { (a, b) -> b >= a } && positions.last() > 0)
        assertEquals(List(starts.size) { it * PcmFormat.BLOCK_SIZE.toLong() }, starts.toList())
        assertEquals(listOf("write", "write", "play"), track.calls.take(3))
    }

    @Test
    fun `una escritura parcial se completa sin perder ni repetir muestras`() = runBlocking {
        val track = FakeTrack(maxPerWrite = 300)
        withTimeout(5_000) { output(track).play(index).take(1).toList() }
        val written = track.samples.toList()
        assertTrue(written.size >= PcmFormat.BLOCK_SIZE)
        assertEquals(List(written.size) { it.toFloat() }, written)
    }

    @Test
    fun `escrituras que devuelven cero sin parar son salida no disponible`() = runBlocking {
        val track = FakeTrack(zeroWrites = true)
        val result = runCatching { withTimeout(5_000) { output(track).play(silence).collect { } } }
        assertSame(TunerFailure.AudioOutputUnavailable, result.exceptionOrNull())
        assertEquals(1, track.count("release"))
    }

    @Test
    fun `al cancelar escribe el bloque de cierre y despues pause, flush y release una vez`() = runBlocking {
        val finishes = AtomicInteger()
        val closing = object : PcmGenerator {
            override fun fill(buffer: FloatArray, startSample: Long) = Unit

            override fun finish(buffer: FloatArray, startSample: Long): Boolean {
                finishes.incrementAndGet()
                return true
            }
        }
        val track = FakeTrack()
        withTimeout(5_000) { output(track).play(closing).first() }
        assertTrue(track.released.await(5, TimeUnit.SECONDS))
        assertEquals(1, finishes.get())
        assertEquals(listOf("write", "pause", "flush", "release"), track.calls.takeLast(4))
        assertEquals(1, track.count("release"))
    }

    @Test
    fun `una escritura atascada no impide cancelar y la salida queda libre`() = runBlocking {
        val stuck = FakeTrack(stallAfter = 3)
        val next = FakeTrack()
        val out = output(stuck, next)
        val job = launch { out.play(silence).collect { } }
        assertTrue(withContext(Dispatchers.IO) { stuck.stalled.await(5, TimeUnit.SECONDS) })
        job.cancel()
        assertJoined(job)
        assertEquals(1, stuck.count("release"))
        // El Mutex se libero: otra reproduccion arranca.
        withTimeout(5_000) { out.play(silence).first() }
        assertEquals(1, next.count("release"))
    }

    @Test
    fun `un error de escritura libera una vez y llega como salida no disponible`() = runBlocking {
        val track = FakeTrack(errorAt = 2)
        val result = runCatching { withTimeout(5_000) { output(track).play(silence).collect { } } }
        assertSame(TunerFailure.AudioOutputUnavailable, result.exceptionOrNull())
        assertEquals(1, track.count("release"))
    }

    @Test
    fun `si play de la pista lanza llega como salida no disponible y se libera`() = runBlocking {
        val track = FakeTrack(playFailure = IllegalStateException("play"))
        val result = runCatching { withTimeout(5_000) { output(track).play(silence).collect { } } }
        assertSame(TunerFailure.AudioOutputUnavailable, result.exceptionOrNull())
        assertEquals(1, track.count("release"))
    }

    @Test
    fun `si el generador lanza llega como salida no disponible`() = runBlocking {
        val track = FakeTrack()
        val boom = PcmGenerator { _, _ -> error("fill") }
        val result = runCatching { withTimeout(5_000) { output(track).play(boom).collect { } } }
        assertSame(TunerFailure.AudioOutputUnavailable, result.exceptionOrNull())
        assertEquals(1, track.count("release"))
    }

    @Test
    fun `si la pista no se puede crear llega como salida no disponible`() = runBlocking {
        val out = AudioTrackOutput({ error("no track") }, dispatcher)
        val result = runCatching { withTimeout(5_000) { out.play(silence).collect { } } }
        assertSame(TunerFailure.AudioOutputUnavailable, result.exceptionOrNull())
    }

    @Test
    fun `cancelar mientras se espera el Mutex no abre pista ni afecta a la primera`() = runBlocking {
        val first = FakeTrack()
        val created = AtomicInteger()
        val out = AudioTrackOutput({ created.incrementAndGet().let { first } }, dispatcher)
        val holder = launch { out.play(silence).collect { } }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (first.count("play") == 0) {
            assertTrue(System.nanoTime() < deadline, "la pista nunca llego a play")
            kotlinx.coroutines.yield()
        }
        val waiting = launch { out.play(silence).collect { } }
        kotlinx.coroutines.delay(50)
        waiting.cancel()
        assertJoined(waiting)
        assertEquals(1, created.get())
        assertTrue(holder.isActive)
        holder.cancel()
        assertJoined(holder)
        assertEquals(1, first.count("release"))
    }

    @Test
    fun `la posicion se corrige cuando el contador de 32 bits desborda`() {
        val position = PlaybackPosition()
        assertEquals(100L, position.update(100))
        assertEquals(Int.MAX_VALUE + 1L, position.update(Int.MIN_VALUE))
        assertEquals(1L shl 32, position.update(0))
        assertEquals((1L shl 32) + 5, position.update(5))
    }

    private companion object {
        const val ERROR_DEAD_OBJECT = -6
    }
}
