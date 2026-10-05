package com.violinstudio.data.feature.tuner.datasource.audio

import com.violinstudio.domain.feature.tuner.failure.TunerFailure
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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

class AudioRecordSourceTest {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "audio-in-test").apply { isDaemon = true } }
    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    @AfterEach
    fun tearDown() {
        executor.shutdownNow()
    }

    /** Lecturas guionadas; despues, chunks completos con pausa de 1 ms (como una lectura bloqueante real). */
    private class FakeRecorder(
        private val reads: MutableList<Int> = mutableListOf(),
        private val startFailure: Throwable? = null,
        private val blockUntilStopped: Boolean = false,
        private val onCreate: () -> Unit = {}
    ) : PcmRecorder {
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val released = CountDownLatch(1)
        val readEntered = CountDownLatch(1)
        val stopCalled = CountDownLatch(1)
        private val sequence = AtomicInteger()

        init {
            onCreate()
        }

        override fun start() {
            calls += "start"
            startFailure?.let { throw it }
        }

        override fun read(buffer: ShortArray): Int {
            readEntered.countDown()
            if (blockUntilStopped) {
                stopCalled.await(READ_BLOCK_LIMIT_SECONDS, TimeUnit.SECONDS)
                return 0
            }
            Thread.sleep(1)
            val n = if (reads.isEmpty()) buffer.size else reads.removeAt(0)
            if (n > 0) buffer.fill(if (reads.isEmpty()) sequence.incrementAndGet().toShort() else 16384, 0, n)
            return n
        }

        override fun stop() {
            calls += "stop"
            stopCalled.countDown()
        }

        override fun release() {
            calls += "release"
            released.countDown()
        }

        fun awaitReleased() = assertTrue(released.await(5, TimeUnit.SECONDS), "release no llamado")
    }
    private fun source(granted: Boolean = true, factory: PcmRecorderFactory): AudioRecordSource =
        AudioRecordSource(factory, { granted }, dispatcher)

    @Test
    fun `emite chunks normalizados y libera una sola vez al cancelar`() = runBlocking {
        val recorder = FakeRecorder(reads = mutableListOf(4, 4))
        var requested = 0
        val items = source {
            requested = it
            recorder
        }.frames(4).take(2).toList()
        recorder.awaitReleased()
        assertEquals(4, requested)
        assertEquals(0.5f, items[0][0], 0f)
        assertEquals(listOf("start", "stop", "release"), recorder.calls.toList())
    }

    @Test
    fun `un error de lectura ocupado falla y libera`() = runBlocking {
        val recorder = FakeRecorder(reads = mutableListOf(2, -6))
        val received = mutableListOf<Int>()
        val failure = runCatching { source { recorder }.frames(4).collect { received += it.size } }.exceptionOrNull()
        assertSame(TunerFailure.MicBusy, failure)
        recorder.awaitReleased()
        assertEquals(listOf("start", "stop", "release"), recorder.calls.toList())
    }

    @Test
    fun `fallo al crear el grabador se mapea`() = runBlocking {
        val unavailable = runCatching { source { throw TunerFailure.MicUnavailable }.frames(4).first() }
        assertSame(TunerFailure.MicUnavailable, unavailable.exceptionOrNull())
        val denied = runCatching { source { throw SecurityException("x") }.frames(4).first() }
        assertSame(TunerFailure.MicPermissionDenied, denied.exceptionOrNull())
    }

    @Test
    fun `fallo al empezar mapea la excepcion y libera una vez`() = runBlocking {
        val recorder = FakeRecorder(startFailure = TunerFailure.MicBusy)
        val failure = runCatching { source { recorder }.frames(4).first() }.exceptionOrNull()
        assertSame(TunerFailure.MicBusy, failure)
        recorder.awaitReleased()
        assertEquals(listOf("start", "stop", "release"), recorder.calls.toList())
    }

    @Test
    fun `sin permiso falla con MicPermissionDenied sin crear el grabador`() = runBlocking {
        var created = false
        val denied = runCatching {
            source(granted = false) {
                created = true
                error("no")
            }.frames(4).first()
        }
        assertSame(TunerFailure.MicPermissionDenied, denied.exceptionOrNull())
        assertTrue(!created)
    }

    @Test
    fun `con permiso crea el grabador`() = runBlocking {
        val recorder = FakeRecorder()
        assertEquals(4, source(granted = true) { recorder }.frames(4).first().size)
    }

    @Test
    fun `una lectura de cero muestras no emite y los chunks parciales conservan su longitud`() = runBlocking {
        val recorder = FakeRecorder(reads = mutableListOf(0, 3))
        assertEquals(3, source { recorder }.frames(4).first().size)
    }

    @Test
    fun `demasiadas lecturas vacias seguidas fallan como no disponible`() = runBlocking {
        val recorder = FakeRecorder(reads = MutableList(100) { 0 })
        val failure = runCatching { source { recorder }.frames(4).first() }.exceptionOrNull()
        assertSame(TunerFailure.MicUnavailable, failure)
        recorder.awaitReleased()
    }

    @Test
    fun `cancelar desbloquea una lectura bloqueada y libera una vez`() = runBlocking {
        val recorder = FakeRecorder(blockUntilStopped = true)
        withTimeout(10_000) {
            val job = launch(Dispatchers.Default) { source { recorder }.frames(4).collect { } }
            assertTrue(recorder.readEntered.await(5, TimeUnit.SECONDS))
            job.cancel()
            job.join()
        }
        recorder.awaitReleased()
        assertEquals(listOf("start", "stop", "release"), recorder.calls.toList())
    }

    @Test
    fun `capturas sucesivas nunca tienen dos grabadores vivos`() = runBlocking {
        val alive = AtomicInteger()
        val maxAlive = AtomicInteger()
        val recorders = mutableListOf<FakeRecorder>()
        val src = source {
            FakeRecorder(onCreate = { maxAlive.accumulateAndGet(alive.incrementAndGet(), ::maxOf) })
                .also { recorders += it }
        }
        repeat(3) {
            src.frames(4).first()
            recorders.last().awaitReleased()
            alive.decrementAndGet()
        }
        assertEquals(3, recorders.size)
        assertEquals(1, maxAlive.get())
    }

    @Test
    fun `una coleccion solapada espera a que la anterior libere`() = runBlocking {
        val first = FakeRecorder(blockUntilStopped = true)
        val second = FakeRecorder()
        val queue = mutableListOf(first, second)
        val src = source { queue.removeAt(0) }
        withTimeout(10_000) {
            val job = launch(Dispatchers.Default) { src.frames(4).collect { } }
            assertTrue(first.readEntered.await(5, TimeUnit.SECONDS))
            val second2 = launch(Dispatchers.Default) { src.frames(4).first() }
            Thread.sleep(50)
            assertEquals(1, queue.size)
            job.cancel()
            job.join()
            second2.join()
        }
        first.awaitReleased()
        second.awaitReleased()
    }

    @Test
    fun `un consumidor lento descarta audio antiguo`() = runBlocking {
        val recorder = FakeRecorder()
        val values = mutableListOf<Float>()
        withTimeout(10_000) {
            source { recorder }.frames(4).take(4).collect {
                values += it[0]
                Thread.sleep(40)
            }
        }
        recorder.awaitReleased()
        assertEquals(4, values.size)
        assertTrue((values[3] - values[0]) * 32768f > 3.5f, "no se descarto nada: $values")
    }

    @Test
    fun `cancelar justo tras entrar en read nunca deja el stop sin llamar`() = runBlocking {
        repeat(CANCEL_RACE_ITERATIONS) {
            val recorder = FakeRecorder(blockUntilStopped = true)
            val job = launch(Dispatchers.Default) { source { recorder }.frames(4).collect { } }
            assertTrue(recorder.readEntered.await(5, TimeUnit.SECONDS))
            job.cancel()
            // Sin withTimeout: un hijo bloqueado en read impediria que el propio timeout terminase.
            assertTrue(recorder.stopCalled.await(5, TimeUnit.SECONDS), "stop no llamado en la iteracion $it")
            job.join()
            recorder.awaitReleased()
        }
    }

    private companion object {
        const val CANCEL_RACE_ITERATIONS = 300
        const val READ_BLOCK_LIMIT_SECONDS = 10L
    }
}
