package com.violinstudio.data.commons.audio

import android.media.AudioManager
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Foco de audio de [AudioTrackOutput]: se pide antes de sonar, se pierde sin reanudar y se abandona una vez. */
class AudioTrackOutputFocusTest {
    private val executor = Executors.newSingleThreadExecutor {
        Thread(it, "audio-out-focus-test").apply { isDaemon = true }
    }
    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @AfterEach
    fun tearDown() {
        executor.shutdownNow()
    }

    /** Foco fake: concede o deniega, registra en [events] y guarda el aviso de perdida para dispararlo a mano. */
    private inner class FakeFocus(private val grant: Boolean = true) : AudioFocus {
        val requests = AtomicInteger()
        val abandons = AtomicInteger()

        @Volatile var onLoss: (() -> Unit)? = null

        override fun request(onLoss: () -> Unit): AudioFocusLease? {
            requests.incrementAndGet()
            events += "request"
            if (!grant) return null
            this.onLoss = onLoss
            return AudioFocusLease {
                abandons.incrementAndGet()
                events += "abandon"
            }
        }
    }

    /** Pista fake; tras [stallAfter] escrituras `write` se atasca hasta `pause`/`release` (como un `AudioTrack` real). */
    private inner class Track(
        private val stallAfter: Int = Int.MAX_VALUE,
        private val failWrites: Boolean = false
    ) : PcmTrack {
        val released = CountDownLatch(1)
        val stalled = CountDownLatch(1)
        val releases = AtomicInteger()
        private val unblock = CountDownLatch(1)
        private val writes = AtomicInteger()
        private var frames = 0L

        override fun play() = Unit

        override fun write(buffer: FloatArray, offset: Int, size: Int): Int {
            if (failWrites) return -6
            if (writes.incrementAndGet() > stallAfter) {
                stalled.countDown()
                unblock.await(10, TimeUnit.SECONDS)
                return -6
            }
            events += "write"
            Thread.sleep(1)
            frames += size
            return size
        }

        override fun playbackHeadPosition(): Int = frames.toInt()

        override fun pause() {
            unblock.countDown()
        }

        override fun flush() = Unit

        override fun release() {
            releases.incrementAndGet()
            events += "release"
            unblock.countDown()
            released.countDown()
        }
    }

    private val silence = PcmGenerator { _, _ -> }

    private fun output(focus: AudioFocus, track: () -> PcmTrack): AudioTrackOutput = AudioTrackOutput(
        {
            events += "create"
            track()
        },
        focus,
        dispatcher
    )

    private suspend fun assertJoined(job: Job) {
        val done = CountDownLatch(1)
        job.invokeOnCompletion { done.countDown() }
        assertTrue(withContext(Dispatchers.IO) { done.await(5, TimeUnit.SECONDS) }, "el job no termino a tiempo")
    }

    @Test
    fun `pide el foco antes de crear la pista y de la primera escritura`() = runBlocking {
        val focus = FakeFocus()
        withTimeout(5_000) { output(focus) { Track() }.play(silence).first() }
        assertEquals("request", events.first())
        assertTrue(events.indexOf("request") < events.indexOf("create"))
        assertTrue(events.indexOf("request") < events.indexOf("write"))
    }

    @Test
    fun `si se deniega el foco no suena y llega como salida no disponible`() = runBlocking {
        val focus = FakeFocus(grant = false)
        val created = AtomicInteger()
        val out = AudioTrackOutput(
            {
                created.incrementAndGet()
                Track()
            },
            focus,
            dispatcher
        )
        val result = runCatching { withTimeout(5_000) { out.play(silence).collect { } } }
        assertSame(TunerFailure.AudioOutputUnavailable, result.exceptionOrNull())
        assertEquals(0, created.get())
        assertEquals(0, focus.abandons.get())
    }

    @Test
    fun `al perder el foco la reproduccion termina sin error, libera la pista y abandona una vez`() = runBlocking {
        val focus = FakeFocus()
        val track = Track()
        val job = launch { output(focus) { track }.play(silence).collect { } }
        withContext(Dispatchers.IO) { while (events.count { it == "write" } < 3) Thread.sleep(1) }
        focus.onLoss!!.invoke()
        assertJoined(job)
        assertFalse(job.isCancelled, "perder el foco no es un error")
        assertEquals(1, track.releases.get())
        assertEquals(1, focus.abandons.get())
    }

    @Test
    fun `la perdida de foco llega desde otro hilo con la escritura atascada sin colgarse`() = runBlocking {
        val focus = FakeFocus()
        val track = Track(stallAfter = 3)
        val job = launch { output(focus) { track }.play(silence).collect { } }
        assertTrue(withContext(Dispatchers.IO) { track.stalled.await(5, TimeUnit.SECONDS) })
        val caller = Thread { focus.onLoss!!.invoke() }.apply { start() }
        withContext(Dispatchers.IO) { caller.join(5_000) }
        assertFalse(caller.isAlive)
        assertJoined(job)
        assertEquals(1, track.releases.get())
        assertEquals(1, focus.abandons.get())
    }

    @Test
    fun `cancelar abandona el foco una vez tras liberar la pista`() = runBlocking {
        val focus = FakeFocus()
        val track = Track()
        withTimeout(5_000) { output(focus) { track }.play(silence).first() }
        assertTrue(track.released.await(5, TimeUnit.SECONDS))
        assertEquals(1, focus.abandons.get())
        assertEquals(listOf("release", "abandon"), events.takeLast(2))
    }

    @Test
    fun `un error de escritura abandona el foco una vez`() = runBlocking {
        val focus = FakeFocus()
        val track = Track(failWrites = true)
        val result = runCatching { withTimeout(5_000) { output(focus) { track }.play(silence).collect { } } }
        assertSame(TunerFailure.AudioOutputUnavailable, result.exceptionOrNull())
        assertEquals(1, focus.abandons.get())
    }

    @Test
    fun `si la pista no se puede crear se abandona el foco una vez`() = runBlocking {
        val focus = FakeFocus()
        val out = AudioTrackOutput({ error("no track") }, focus, dispatcher)
        val result = runCatching { withTimeout(5_000) { out.play(silence).collect { } } }
        assertSame(TunerFailure.AudioOutputUnavailable, result.exceptionOrNull())
        assertEquals(1, focus.abandons.get())
    }

    @Test
    fun `cancelar con la escritura atascada abandona el foco una vez`() = runBlocking {
        val focus = FakeFocus()
        val track = Track(stallAfter = 3)
        val job = launch { output(focus) { track }.play(silence).collect { } }
        assertTrue(withContext(Dispatchers.IO) { track.stalled.await(5, TimeUnit.SECONDS) })
        job.cancel()
        assertJoined(job)
        assertEquals(1, focus.abandons.get())
    }

    @Test
    fun `solo la perdida y la perdida transitoria paran la reproduccion y el duck sigue sonando`() {
        assertTrue(AndroidAudioFocus.endsPlayback(AudioManager.AUDIOFOCUS_LOSS))
        assertTrue(AndroidAudioFocus.endsPlayback(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
        assertFalse(AndroidAudioFocus.endsPlayback(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK))
        assertFalse(AndroidAudioFocus.endsPlayback(AudioManager.AUDIOFOCUS_GAIN))
    }
}

/** Foco siempre concedido: para las pruebas de la salida que no tratan del foco. */
internal val grantedFocus = AudioFocus { AudioFocusLease { } }
