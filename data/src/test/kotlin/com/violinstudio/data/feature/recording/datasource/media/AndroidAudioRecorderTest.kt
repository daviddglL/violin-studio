package com.violinstudio.data.feature.recording.datasource.media

import com.violinstudio.domain.feature.recording.failure.RecordingFailure
import com.violinstudio.domain.feature.recording.model.RecordingOutcome
import com.violinstudio.domain.feature.recording.model.RecordingSpec
import com.violinstudio.domain.feature.recording.repository.RecordingHandle
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** REQ-REC-03: captura local con `MediaRecorderFactory` fake (sin hardware). */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidAudioRecorderTest {
    @TempDir
    lateinit var root: File

    private class FakeSession : MediaRecorderSession {
        val released = AtomicInteger()
        val stopped = AtomicInteger()
        val startedMaxMs = AtomicReference<Long?>(null)
        val stopThread = AtomicReference<String?>(null)
        val listener = AtomicReference<MediaRecorderSession.Listener?>(null)
        val output = AtomicReference<File?>(null)
        var startFailure: Throwable? = null
        var stopFailure: Throwable? = null
        var bytes = 4_096L
        var startGate: CountDownLatch? = null
        val startEntered = CountDownLatch(1)
        var stopGate: CountDownLatch? = null
        val stopEntered = CountDownLatch(1)

        /** Se ejecuta dentro de `start`, con el fichero ya escrito (avisos tempranos del MediaRecorder). */
        var duringStart: (MediaRecorderSession.Listener) -> Unit = {}

        override fun start(output: File, maxDurationMs: Long, listener: MediaRecorderSession.Listener) {
            startEntered.countDown()
            startGate?.await(GATE_SECONDS, TimeUnit.SECONDS)
            startFailure?.let { throw it }
            this.output.set(output)
            this.listener.set(listener)
            startedMaxMs.set(maxDurationMs)
            RandomAccessFile(output, "rw").use { it.setLength(bytes) }
            duringStart(listener)
        }

        override fun stop() {
            stopEntered.countDown()
            stopGate?.await(GATE_SECONDS, TimeUnit.SECONDS)
            stopThread.set(Thread.currentThread().name)
            stopped.incrementAndGet()
            stopFailure?.let { throw it }
        }

        override fun release() {
            released.incrementAndGet()
        }
    }

    private class FakeFactory : MediaRecorderFactory {
        val sessions: MutableList<FakeSession> = java.util.Collections.synchronizedList(mutableListOf())
        val firstCreated = java.util.concurrent.CompletableFuture<FakeSession>()
        var configure: (FakeSession) -> Unit = {}

        override fun create(): MediaRecorderSession = FakeSession().also {
            configure(it)
            sessions += it
            firstCreated.complete(it)
        }
    }

    private val factory = FakeFactory()
    private val dirs = RecordingsDirectory { uid -> File(root, "recordings/$uid") }
    private fun spec(id: String = "rec-1", autoStopMs: Long = 59_500) =
        RecordingSpec(id, "uid1", autoStopMs + 500, autoStopMs, consentEpoch = 4)

    // Reloj por defecto: cada lectura avanza 1 s (inicio y cierre quedan separados por mas que MIN_DURATION_MS).
    private val ticks = AtomicLong(0)

    private fun recorder(dispatcher: CoroutineDispatcher = Dispatchers.IO, now: () -> Long = { ticks.getAndAdd(1_000) }) =
        AndroidAudioRecorder(factory, dirs, dispatcher, now)

    private fun part(id: String = "rec-1") = File(root, "recordings/uid1/$id.part")
    private fun m4a(id: String = "rec-1") = File(root, "recordings/uid1/$id.m4a")
    private fun files() = File(root, "recordings").walkTopDown().filter { it.isFile }.map { it.name }.toList()

    @Test
    fun `escribe el parcial, configura el limite exacto y al parar renombra a m4a`() = runBlocking {
        var clock = 1_000L
        val handle = recorder(now = { clock }).start(spec()).getOrThrow()
        assertTrue(part().isFile)
        assertFalse(m4a().exists())
        assertEquals(59_500L, factory.sessions.single().startedMaxMs.get())
        clock += 30_000
        val take = handle.stop().getOrThrow()
        assertFalse(part().exists())
        assertTrue(m4a().isFile)
        assertEquals(m4a().path, take.path)
        assertEquals(4_096L, take.sizeBytes)
        assertEquals(30_000L, take.durationMs)
        assertEquals(4L, take.consentEpoch)
        assertEquals("rec-1", take.id)
        assertEquals(1, factory.sessions.single().released.get())
    }

    @Test
    fun `discard borra el parcial y libera, y es idempotente`() = runBlocking {
        val handle = recorder().start(spec()).getOrThrow()
        handle.discard()
        handle.discard()
        assertEquals(emptyList<String>(), files())
        assertEquals(1, factory.sessions.single().released.get())
        assertTrue(handle.stop().isFailure)
    }

    @Test
    fun `discard tras entregar la toma no borra el m4a y deleteTake es la forma explicita de borrarlo`() = runBlocking {
        val rec = recorder()
        val handle = rec.start(spec()).getOrThrow()
        val take = handle.stop().getOrThrow()
        handle.discard()
        handle.discard()
        assertTrue(m4a().isFile)
        assertEquals(listOf("rec-1.m4a"), files())
        rec.deleteTake(take)
        assertEquals(emptyList<String>(), files())
        rec.deleteTake(take)
    }

    @Test
    fun `discard tras una parada automatica tampoco borra la toma entregada`() = runBlocking {
        val rec = recorder()
        val handle = rec.start(spec()).getOrThrow()
        factory.sessions.single().listener.get()!!.onMaxDurationReached()
        val outcome = handle.awaitOutcome() as RecordingOutcome.AutoStopped
        handle.discard()
        assertTrue(m4a().isFile)
        rec.deleteTake(outcome.take)
        assertEquals(emptyList<String>(), files())
    }

    @Test
    fun `deleteTake solo borra la ruta propia de la toma`() = runBlocking {
        val rec = recorder()
        val take = rec.start(spec()).getOrThrow().stop().getOrThrow()
        val other = File(root, "keep.m4a").apply { writeText("x") }
        rec.deleteTake(take.copy(path = other.path))
        assertTrue(other.isFile)
        assertTrue(m4a().isFile)
        rec.deleteTake(take.copy(id = "../keep"))
        assertTrue(other.isFile)
    }

    @Test
    fun `al empezar se borran los parciales huerfanos del uid y la toma actual no se toca`() = runBlocking {
        val dir = File(root, "recordings/uid1").apply { mkdirs() }
        File(dir, "old-1.part").writeText("a")
        File(dir, "old-2.part").writeText("b")
        File(dir, "done.m4a").writeText("keep")
        val otherUid = File(root, "recordings/uid2").apply { mkdirs() }
        File(otherUid, "x.part").writeText("c")
        val handle = recorder().start(spec()).getOrThrow()
        assertEquals(setOf("rec-1.part", "done.m4a"), dir.list()!!.toSet())
        assertTrue(File(otherUid, "x.part").isFile)
        assertEquals(4_096L, part().length())
        handle.discard()
    }

    @Test
    fun `sweepPartials borra solo los parciales del uid`() {
        val dir = File(root, "recordings/uid1").apply { mkdirs() }
        File(dir, "a.part").writeText("a")
        File(dir, "b.m4a").writeText("b")
        assertEquals(1, dirs.sweepPartials("uid1"))
        assertEquals(listOf("b.m4a"), dir.list()!!.toList())
        assertEquals(0, dirs.sweepPartials("uid1"))
        assertEquals(0, dirs.sweepPartials("nunca"))
    }

    @Test
    fun `permiso denegado al arrancar es PermissionDenied y no deja parcial`() = runBlocking {
        factory.configure = { it.startFailure = SecurityException("x") }
        val failure = recorder().start(spec()).exceptionOrNull()
        assertEquals(RecordingFailure.PermissionDenied, failure)
        assertEquals(emptyList<String>(), files())
        assertEquals(1, factory.sessions.single().released.get())
    }

    @Test
    fun `microfono ocupado o fallo de preparacion es MicUnavailable sin parcial`() = runBlocking {
        listOf(IllegalStateException("busy"), IOException("prepare"), RuntimeException("start")).forEach { error ->
            factory.configure = { it.startFailure = error }
            assertEquals(RecordingFailure.MicUnavailable, recorder().start(spec()).exceptionOrNull())
        }
        assertEquals(emptyList<String>(), files())
        assertTrue(factory.sessions.all { it.released.get() == 1 })
    }

    @Test
    fun `un fallo de stop es RecorderFailed, borra el parcial y libera`() = runBlocking {
        factory.configure = { it.stopFailure = RuntimeException("no valid data") }
        val handle = recorder().start(spec()).getOrThrow()
        assertEquals(RecordingFailure.RecorderFailed, handle.stop().exceptionOrNull())
        assertEquals(emptyList<String>(), files())
        assertEquals(1, factory.sessions.single().released.get())
    }

    @Test
    fun `una toma vacia o por encima de 25 MiB se rechaza y se borra`() = runBlocking {
        listOf(0L, 25L * 1024 * 1024 + 1).forEach { size ->
            factory.configure = { it.bytes = size }
            val handle = recorder().start(spec()).getOrThrow()
            assertEquals(RecordingFailure.RecorderFailed, handle.stop().exceptionOrNull())
            assertEquals(emptyList<String>(), files())
        }
    }

    @Test
    fun `ids o uid con caracteres de ruta se rechazan sin crear nada`() = runBlocking {
        listOf(spec(id = "../evil"), spec(id = "a/b"), spec(id = ""), spec().copy(uid = "../x"), spec().copy(uid = ""))
            .forEach { assertEquals(RecordingFailure.RecorderFailed, recorder().start(it).exceptionOrNull()) }
        assertTrue(factory.sessions.isEmpty())
        assertFalse(File(root, "recordings").exists())
    }

    @Test
    fun `solo una toma a la vez y se puede grabar otra tras descartar`() = runBlocking {
        val rec = recorder()
        val first = rec.start(spec()).getOrThrow()
        assertEquals(RecordingFailure.Busy, rec.start(spec("rec-2")).exceptionOrNull())
        assertEquals(1, factory.sessions.size)
        first.discard()
        rec.start(spec("rec-2")).getOrThrow().discard()
        assertEquals(emptyList<String>(), files())
    }

    @Test
    fun `una toma fallida o cerrada libera el recorder para la siguiente`() = runBlocking {
        val rec = recorder()
        rec.start(spec()).getOrThrow().stop().getOrThrow()
        rec.start(spec("rec-2")).getOrThrow().stop().getOrThrow()
        assertEquals(setOf("rec-1.m4a", "rec-2.m4a"), files().toSet())
    }

    @Test
    fun `un error del MediaRecorder en curso fija Failed y borra el parcial`() = runBlocking {
        val handle = recorder().start(spec()).getOrThrow()
        factory.sessions.single().listener.get()!!.onError()
        assertEquals(RecordingOutcome.Failed(RecordingFailure.RecorderFailed), handle.awaitOutcome())
        assertEquals(emptyList<String>(), files())
        assertEquals(1, factory.sessions.single().released.get())
    }

    @Test
    fun `el aviso del limite del MediaRecorder cierra la toma como parada automatica`() = runBlocking {
        val handle = recorder().start(spec()).getOrThrow()
        factory.sessions.single().listener.get()!!.onMaxDurationReached()
        val outcome = handle.awaitOutcome() as RecordingOutcome.AutoStopped
        assertEquals(outcome.take, handle.stop().getOrThrow())
        assertEquals(1, factory.sessions.single().stopped.get())
    }

    @Test
    fun `parada automatica al limite con reloj fake conserva el fichero y stop devuelve esa toma`() = runTest {
        val rec = recorder(StandardTestDispatcher(testScheduler)) { currentTime }
        val handle = rec.start(spec(autoStopMs = 59_500)).getOrThrow()
        advanceTimeBy(59_499)
        runCurrent()
        assertNull(handle.outcome.value)
        assertTrue(part().isFile)
        advanceTimeBy(1)
        runCurrent()
        val outcome = handle.outcome.value as RecordingOutcome.AutoStopped
        assertEquals(59_500L, outcome.take.durationMs)
        assertTrue(m4a().isFile)
        assertFalse(part().exists())
        assertEquals(outcome.take, handle.stop().getOrThrow())
        assertEquals(1, factory.sessions.single().stopped.get())
        assertEquals(1, factory.sessions.single().released.get())
    }

    @Test
    fun `el profesor sigue grabando a 119 s y para a 119,5 s`() = runTest {
        val rec = recorder(StandardTestDispatcher(testScheduler)) { currentTime }
        val handle = rec.start(spec(autoStopMs = 119_500)).getOrThrow()
        advanceTimeBy(119_000)
        runCurrent()
        assertNull(handle.outcome.value)
        advanceTimeBy(500)
        runCurrent()
        assertTrue(handle.outcome.value is RecordingOutcome.AutoStopped)
        handle.discard()
    }

    @Test
    fun `parar a mano cancela el temporizador y no hay parada automatica despues`() = runTest {
        val rec = recorder(StandardTestDispatcher(testScheduler)) { currentTime }
        val handle = rec.start(spec()).getOrThrow()
        advanceTimeBy(10_000)
        val take = handle.stop().getOrThrow()
        assertEquals(10_000L, take.durationMs)
        advanceTimeBy(100_000)
        runCurrent()
        assertNull(handle.outcome.value)
        assertEquals(1, factory.sessions.single().stopped.get())
    }

    @Test
    fun `una toma de menos de 500 ms es TooShort, se borra y libera`() = runTest {
        val rec = recorder(StandardTestDispatcher(testScheduler)) { currentTime }
        val handle = rec.start(spec()).getOrThrow()
        advanceTimeBy(499)
        assertEquals(RecordingFailure.TooShort, handle.stop().exceptionOrNull())
        assertEquals(emptyList<String>(), files())
        assertEquals(1, factory.sessions.single().released.get())
        // El turno queda libre.
        advanceTimeBy(10_000)
        val again = rec.start(spec("rec-2")).getOrThrow()
        advanceTimeBy(500)
        assertEquals(500L, again.stop().getOrThrow().durationMs)
    }

    @Test
    fun `una parada automatica demasiado corta publica Failed(TooShort)`() = runBlocking {
        var clock = 1_000L
        val handle = recorder(now = { clock }).start(spec()).getOrThrow()
        clock += 100
        factory.sessions.single().listener.get()!!.onMaxDurationReached()
        assertEquals(RecordingOutcome.Failed(RecordingFailure.TooShort), handle.awaitOutcome())
        assertEquals(emptyList<String>(), files())
    }

    @Test
    fun `el reloj arranca antes de start y un aviso temprano del limite usa ese inicio`() = runBlocking {
        var clock = 1_000L
        factory.configure = {
            it.duringStart = { listener ->
                clock = 3_000L
                listener.onMaxDurationReached()
            }
        }
        val handle = recorder(now = { clock }).start(spec()).getOrThrow()
        val outcome = handle.awaitOutcome() as RecordingOutcome.AutoStopped
        assertEquals(2_000L, outcome.take.durationMs)
        assertEquals(1, factory.sessions.single().stopped.get())
    }

    @Test
    fun `temporizador y aviso del limite a la vez cierran la toma una sola vez`() = runBlocking {
        repeat(40) { round ->
            val factory = FakeFactory()
            var clock = 0L
            val rec = AndroidAudioRecorder(factory, dirs, Dispatchers.IO) { clock }
            val handle = rec.start(spec("r$round", autoStopMs = 5)).getOrThrow()
            clock = 5_000
            val gate = CountDownLatch(1)
            val racer = Thread {
                gate.await()
                factory.sessions.single().listener.get()!!.onMaxDurationReached()
            }.also { it.start() }
            gate.countDown()
            val outcome = handle.awaitOutcome() as RecordingOutcome.AutoStopped
            racer.join()
            val session = factory.sessions.single()
            waitUntil { session.released.get() == 1 }
            assertEquals(1, session.stopped.get(), "ronda $round")
            assertEquals(1, session.released.get(), "ronda $round")
            assertEquals(outcome.take, handle.stop().getOrThrow())
            assertEquals(listOf("r$round.m4a"), File(root, "recordings/uid1").list()!!.filter { it.startsWith("r$round") })
            rec.deleteTake(outcome.take)
        }
    }

    @Test
    fun `stop se ejecuta en el hilo del recorder y no en el del llamador`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "rec-io-test") }
        try {
            val rec = recorder(executor.asCoroutineDispatcher())
            val handle = rec.start(spec()).getOrThrow()
            val caller = Thread.currentThread().name
            handle.stop().getOrThrow()
            val session = factory.sessions.single()
            // El nombre del hilo lleva el sufijo " @coroutine#n" del depurador de corrutinas.
            assertTrue(session.stopThread.get()!!.startsWith("rec-io-test"), session.stopThread.get())
            assertTrue(caller != session.stopThread.get())
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `cancelar un stop bloqueado no espera al recorder y se libera desde otro hilo`() = runBlocking {
        val gate = CountDownLatch(1)
        factory.configure = { it.stopGate = gate }
        val handle = recorder().start(spec()).getOrThrow()
        val caller = Thread.currentThread().name
        val stopping = async(Dispatchers.Default) { handle.stop() }
        assertTrue(factory.sessions.single().stopEntered.await(5, TimeUnit.SECONDS))
        withTimeout(2_000) { stopping.cancelAndJoin() }
        assertEquals(0, factory.sessions.single().released.get())
        gate.countDown()
        waitUntil { factory.sessions.single().released.get() == 1 }
        // La toma cancelada se descarta: ni parcial ni m4a quedan.
        waitUntil { files().isEmpty() }
        assertTrue(factory.sessions.single().stopThread.get() != caller)
    }

    @Test
    fun `cancelar un start bloqueado descarta la toma cuando termina`() = runBlocking {
        val gate = CountDownLatch(1)
        factory.configure = { it.startGate = gate }
        val rec = recorder()
        val starting = async(Dispatchers.Default) { rec.start(spec()) }
        val session = factory.firstCreated.get(5, TimeUnit.SECONDS)
        assertTrue(session.startEntered.await(5, TimeUnit.SECONDS))
        withTimeout(2_000) { starting.cancelAndJoin() }
        gate.countDown()
        waitUntil { factory.sessions.single().released.get() == 1 }
        waitUntil { files().isEmpty() }
        rec.start(spec("rec-2")).getOrThrow().discard()
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        withTimeout(5_000) { while (!condition()) delay(10) }
    }

    private suspend fun RecordingHandle.awaitOutcome(): RecordingOutcome = withTimeout(5_000) {
        while (outcome.value == null) delay(10)
        outcome.value!!
    }

    private companion object {
        const val GATE_SECONDS = 10L
    }
}
