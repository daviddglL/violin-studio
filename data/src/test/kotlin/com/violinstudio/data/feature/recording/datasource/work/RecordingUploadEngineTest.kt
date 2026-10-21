package com.violinstudio.data.feature.recording.datasource.work

import com.violinstudio.data.feature.recording.datasource.ConsentSnapshot
import com.violinstudio.data.feature.recording.datasource.RecordingRemoteDataSource
import com.violinstudio.data.feature.recording.datasource.RemoteRecordingStatus
import com.violinstudio.data.feature.recording.datasource.UploadRequest
import com.violinstudio.data.feature.recording.datasource.UploadResult
import com.violinstudio.domain.feature.recording.model.FailReason
import com.violinstudio.domain.feature.recording.model.QueueState
import com.violinstudio.domain.feature.recording.model.QueuedRecording
import com.violinstudio.domain.feature.recording.model.RecordedTake
import com.violinstudio.domain.feature.recording.model.RecordingSpec
import com.violinstudio.domain.feature.recording.model.RecordingUploadContract
import com.violinstudio.domain.feature.recording.model.UploadError
import com.violinstudio.domain.feature.recording.repository.Recorder
import com.violinstudio.domain.feature.recording.repository.RecordingHandle
import com.violinstudio.domain.feature.recording.repository.RecordingQueue
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** REQ-REC-04 / REQ-REC-09 / R1 / R3: logica del worker de subida con Storage y Firestore fake (sin Android). */
class RecordingUploadEngineTest {
    @TempDir
    lateinit var root: File

    private class MutableClock(var now: Long = 1_000_000L) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(now)
    }

    private class MemoryQueue : RecordingQueue {
        val items = mutableListOf<QueuedRecording>()
        var failUpdates = false
        var afterUpdate: (() -> Unit)? = null

        override suspend fun enqueue(item: QueuedRecording): Result<Unit> = Result.success(Unit).also { items += item }

        override fun observe(uid: String): Flow<List<QueuedRecording>> = flow {
            emit(items.filter { it.take.uid == uid })
        }

        override suspend fun update(
            uid: String,
            id: String,
            transform: (QueuedRecording) -> QueuedRecording
        ): Result<QueuedRecording?> {
            if (failUpdates) return Result.failure(IOException("disco"))
            val at = items.indexOfFirst { it.id == id && it.take.uid == uid }
            if (at < 0) return Result.success(null)
            items[at] = transform(items[at])
            afterUpdate?.invoke()
            return Result.success(items[at])
        }

        override suspend fun remove(uid: String, id: String): Result<Unit> {
            items.removeAll { it.id == id && it.take.uid == uid }
            return Result.success(Unit)
        }

        override suspend fun clear(uid: String): Result<List<QueuedRecording>> = Result.success(emptyList())
    }

    private class FakeRecorder : Recorder {
        val deleted = mutableListOf<String>()

        override suspend fun start(spec: RecordingSpec): Result<RecordingHandle> =
            Result.failure(IllegalStateException())

        override suspend fun deleteTake(take: RecordedTake) {
            deleted += take.id
        }
    }

    private class FakeRemote : RecordingRemoteDataSource {
        var uid: String? = "A"
        val requests = mutableListOf<UploadRequest>()
        var uploadBlock: suspend (UploadRequest, (String) -> Unit) -> UploadResult = { _, _ -> UploadResult.Uploaded }
        val status = MutableStateFlow<RemoteRecordingStatus?>(null)
        var statusError: Throwable? = null
        var consent: Result<ConsentSnapshot> = Result.success(ConsentSnapshot(granted = true, epoch = 7))
        var exists: Result<Boolean> = Result.success(false)
        val deletedDocs = mutableListOf<String>()
        var deleteDocFailure: Throwable? = null

        override fun currentUid(): String? = uid

        override suspend fun upload(request: UploadRequest, onSession: (String) -> Unit): UploadResult {
            requests += request
            return uploadBlock(request, onSession)
        }

        override suspend fun objectExists(objectPath: String): Result<Boolean> = exists

        override fun observeStatus(uid: String, id: String): Flow<RemoteRecordingStatus?> = flow {
            statusError?.let { throw it }
            status.collect { emit(it) }
        }

        override suspend fun deleteDoc(uid: String, id: String): Result<Unit> =
            deleteDocFailure?.let { Result.failure(it) } ?: Result.success(Unit).also { deletedDocs += id }

        override suspend fun consent(uid: String): Result<ConsentSnapshot> = consent
    }

    private val queue = MemoryQueue()
    private val remote = FakeRemote()
    private val recorder = FakeRecorder()
    private val clock = MutableClock()
    private val engine by lazy { RecordingUploadEngine(queue, remote, recorder, clock) }

    private fun take(id: String = "r1", create: Boolean = true): RecordedTake {
        val file = File(root, "$id.m4a")
        if (create) file.writeBytes(ByteArray(16))
        return RecordedTake(id, "A", file.path, 5_000, 16, 7)
    }

    private fun queued(
        state: QueueState = QueueState.RECORDED,
        id: String = "r1",
        create: Boolean = true,
        change: (QueuedRecording) -> QueuedRecording = { it }
    ): QueuedRecording = change(QueuedRecording(take(id, create), state = state)).also { queue.items += it }

    private fun only() = queue.items.single()

    // --- aislamiento y casos sin trabajo ---

    @Test
    fun `con otro usuario autenticado reintenta sin subir ni tocar la cola`() = runTest {
        val item = queued()
        remote.uid = "B"
        assertEquals(EngineResult.RETRY, engine.run("A", "r1"))
        remote.uid = null
        assertEquals(EngineResult.RETRY, engine.run("A", "r1"))
        assertTrue(remote.requests.isEmpty())
        assertEquals(item, only())
    }

    @Test
    fun `un elemento que ya no esta en la cola es trabajo terminado`() = runTest {
        assertEquals(EngineResult.DONE, engine.run("A", "ghost"))
        assertTrue(remote.requests.isEmpty())
    }

    // --- subida ---

    @Test
    fun `sube con el contrato de reglas y la confirmacion ready borra fichero y elemento`() = runTest {
        val item = queued()
        remote.status.value = RemoteRecordingStatus.READY
        assertEquals(EngineResult.DONE, engine.run("A", "r1"))
        val request = remote.requests.single()
        assertEquals(RecordingUploadContract.objectPath(item.take), request.objectPath)
        assertEquals("users/A/recordings/r1.m4a", request.objectPath)
        assertEquals("audio/mp4", request.contentType)
        assertEquals(RecordingUploadContract.customMetadata(item.take), request.customMetadata)
        assertEquals("7", request.customMetadata[RecordingUploadContract.EPOCH_KEY])
        assertEquals(item.take.path, request.filePath)
        assertNull(request.sessionUri)
        assertEquals(listOf("r1"), recorder.deleted)
        assertTrue(queue.items.isEmpty(), "DONE se quita de la cola: no cuenta para el cupo")
    }

    @Test
    fun `una toma rechazada por el servidor tambien termina y borra fichero y elemento`() = runTest {
        queued()
        remote.status.value = RemoteRecordingStatus.REJECTED
        assertEquals(EngineResult.DONE, engine.run("A", "r1"))
        assertEquals(listOf("r1"), recorder.deleted)
        assertTrue(queue.items.isEmpty())
    }

    @Test
    fun `tras subir y sin doc el elemento queda uploaded con la hora y reintenta`() = runTest {
        queued()
        assertEquals(EngineResult.RETRY, engine.run("A", "r1"))
        val item = only()
        assertEquals(QueueState.UPLOADED, item.state)
        assertEquals(clock.now, item.uploadedAt)
        assertNull(item.sessionUri)
        assertEquals(1, item.attempts)
        assertTrue(recorder.deleted.isEmpty())
    }

    @Test
    fun `guarda el sessionUri en cada progreso y la siguiente ejecucion reanuda con el`() = runTest {
        queued()
        remote.uploadBlock = { _, onSession ->
            onSession("sess-1")
            onSession("sess-2")
            UploadResult.Failed(UploadError.Network)
        }
        assertEquals(EngineResult.RETRY, engine.run("A", "r1"))
        assertEquals("sess-2", only().sessionUri)
        assertEquals(QueueState.UPLOADING, only().state)
        remote.uploadBlock = { _, _ -> UploadResult.Uploaded }
        remote.status.value = RemoteRecordingStatus.READY
        assertEquals(EngineResult.DONE, engine.run("A", "r1"))
        assertEquals("sess-2", remote.requests.last().sessionUri)
    }

    @Test
    fun `un sessionUri caducado 404 o 410 se descarta y se reintenta desde cero`() = runTest {
        queued { it.copy(state = QueueState.UPLOADING, sessionUri = "viejo") }
        remote.uploadBlock = { _, _ -> UploadResult.Failed(UploadError.Http(410)) }
        assertEquals(EngineResult.RETRY, engine.run("A", "r1"))
        assertNull(only().sessionUri)
        assertEquals(QueueState.UPLOADING, only().state)
    }

    @Test
    fun `fallos transitorios reintentan y conservan el fichero`() = runTest {
        queued()
        listOf(UploadError.Network, UploadError.RetryLimitExceeded, UploadError.Http(503)).forEach {
            remote.uploadBlock = { _, _ -> UploadResult.Failed(it) }
            assertEquals(EngineResult.RETRY, engine.run("A", "r1"))
        }
        assertEquals(3, only().attempts)
        assertTrue(recorder.deleted.isEmpty())
    }

    @Test
    fun `cuota o tamano o desconocido pasan a failed con su motivo y el fichero queda`() = runTest {
        val cases = listOf(
            UploadError.QuotaExceeded to FailReason.QUOTA,
            UploadError.TooLarge to FailReason.SIZE,
            UploadError.Other to FailReason.UNKNOWN
        )
        cases.forEach { (error, reason) ->
            queue.items.clear()
            queued()
            remote.uploadBlock = { _, _ -> UploadResult.Failed(error) }
            assertEquals(EngineResult.FAILED, engine.run("A", "r1"))
            assertEquals(QueueState.FAILED, only().state)
            assertEquals(reason, only().lastError)
        }
        assertTrue(recorder.deleted.isEmpty())
    }

    @Test
    fun `un objeto que ya existe 412 cuenta como subido y sigue a la confirmacion`() = runTest {
        queued()
        remote.uploadBlock = { _, _ -> UploadResult.Failed(UploadError.Http(412)) }
        remote.status.value = RemoteRecordingStatus.READY
        assertEquals(EngineResult.DONE, engine.run("A", "r1"))
        assertTrue(queue.items.isEmpty())
    }

    @Test
    fun `un 403 con el consentimiento revocado borra el fichero y el elemento de inmediato (R1)`() = runTest {
        queued()
        remote.uploadBlock = { _, _ -> UploadResult.Failed(UploadError.Unauthorized(false)) }
        remote.consent = Result.success(ConsentSnapshot(granted = false, epoch = 7))
        assertEquals(EngineResult.FAILED, engine.run("A", "r1"))
        assertEquals(listOf("r1"), recorder.deleted)
        assertTrue(queue.items.isEmpty())
    }

    @Test
    fun `un 403 con la epoca de consentimiento cambiada tambien borra la toma (sello obsoleto)`() = runTest {
        queued()
        remote.uploadBlock = { _, _ -> UploadResult.Failed(UploadError.Http(403)) }
        remote.consent = Result.success(ConsentSnapshot(granted = true, epoch = 8))
        assertEquals(EngineResult.FAILED, engine.run("A", "r1"))
        assertEquals(listOf("r1"), recorder.deleted)
        assertTrue(queue.items.isEmpty())
    }

    @Test
    fun `un 403 con el consentimiento vigente es failed de permiso y conserva el fichero`() = runTest {
        queued()
        remote.uploadBlock = { _, _ -> UploadResult.Failed(UploadError.Unauthorized(false)) }
        assertEquals(EngineResult.FAILED, engine.run("A", "r1"))
        assertEquals(FailReason.PERMISSION, only().lastError)
        assertTrue(recorder.deleted.isEmpty())
    }

    @Test
    fun `un 403 sin poder leer el consentimiento reintenta en vez de decidir a ciegas`() = runTest {
        queued()
        remote.uploadBlock = { _, _ -> UploadResult.Failed(UploadError.Unauthorized(false)) }
        remote.consent = Result.failure(IOException("offline"))
        assertEquals(EngineResult.RETRY, engine.run("A", "r1"))
        assertTrue(recorder.deleted.isEmpty())
        assertEquals(1, queue.items.size)
    }

    @Test
    fun `tras 10 intentos pasa a failed sin subir y un fichero ausente tambien`() = runTest {
        queued { it.copy(attempts = RecordingUploadEngine.MAX_ATTEMPTS) }
        assertEquals(EngineResult.FAILED, engine.run("A", "r1"))
        assertEquals(FailReason.UNKNOWN, only().lastError)
        assertTrue(remote.requests.isEmpty())
        queue.items.clear()
        queued(id = "r2", create = false)
        assertEquals(EngineResult.FAILED, engine.run("A", "r2"))
        assertEquals(FailReason.UNKNOWN, only().lastError)
        assertTrue(remote.requests.isEmpty())
    }

    @Test
    fun `un elemento failed no se reintenta solo y un fallo de la cola reintenta`() = runTest {
        queued(QueueState.FAILED)
        assertEquals(EngineResult.FAILED, engine.run("A", "r1"))
        assertTrue(remote.requests.isEmpty())
        queue.items.clear()
        queued()
        queue.failUpdates = true
        assertEquals(EngineResult.RETRY, engine.run("A", "r1"))
        assertTrue(remote.requests.isEmpty())
    }

    // --- confirmacion ---

    @Test
    fun `sin doc antes de 24 h reintenta sin cambiar el estado`() = runTest {
        queued(QueueState.UPLOADED) { it.copy(uploadedAt = clock.now) }
        clock.now += RecordingUploadEngine.CONFIRM_DEADLINE_MS - 1
        assertEquals(EngineResult.RETRY, engine.run("A", "r1"))
        assertEquals(QueueState.UPLOADED, only().state)
        assertTrue(remote.requests.isEmpty())
    }

    @Test
    fun `sin doc a las 24 h reintenta una vez y a las 24 h siguientes pasa a failed`() = runTest {
        queued(QueueState.UPLOADED) { it.copy(uploadedAt = clock.now) }
        clock.now += RecordingUploadEngine.CONFIRM_DEADLINE_MS
        assertEquals(EngineResult.RETRY, engine.run("A", "r1"))
        assertTrue(only().confirmRetried)
        assertEquals(clock.now, only().uploadedAt)
        assertEquals(QueueState.UPLOADED, only().state)
        clock.now += RecordingUploadEngine.CONFIRM_DEADLINE_MS
        assertEquals(EngineResult.FAILED, engine.run("A", "r1"))
        assertEquals(QueueState.FAILED, only().state)
        assertEquals(FailReason.UNKNOWN, only().lastError)
        assertTrue(recorder.deleted.isEmpty())
    }

    @Test
    fun `un uploaded sin hora (cola vieja) la fija ahora y un fallo del listener reintenta`() = runTest {
        queued(QueueState.UPLOADED)
        remote.statusError = IOException("listener")
        assertEquals(EngineResult.RETRY, engine.run("A", "r1"))
        assertEquals(clock.now, only().uploadedAt)
    }

    @Test
    fun `si la cola no deja borrar el elemento tras confirmar reintenta y el fichero ya esta borrado`() = runTest {
        queued(QueueState.UPLOADED) { it.copy(uploadedAt = clock.now) }
        remote.status.value = RemoteRecordingStatus.READY
        val broken = object : RecordingQueue by queue {
            override suspend fun remove(uid: String, id: String): Result<Unit> = Result.failure(IOException())
        }
        assertEquals(EngineResult.RETRY, RecordingUploadEngine(broken, remote, recorder, clock).run("A", "r1"))
    }

    // --- cancelacion (R3) ---

    @Test
    fun `un elemento cancelado sin subir se borra con su fichero sin tocar la red`() = runTest {
        queued { it.copy(cancelled = true) }
        assertEquals(EngineResult.DONE, engine.run("A", "r1"))
        assertEquals(listOf("r1"), recorder.deleted)
        assertTrue(queue.items.isEmpty())
        assertTrue(remote.requests.isEmpty())
    }

    @Test
    fun `cancelado subiendo y sin objeto en Storage se borra sin mas`() = runTest {
        queued(QueueState.UPLOADING) { it.copy(cancelled = true) }
        remote.exists = Result.success(false)
        assertEquals(EngineResult.DONE, engine.run("A", "r1"))
        assertTrue(queue.items.isEmpty())
        assertTrue(remote.deletedDocs.isEmpty())
    }

    @Test
    fun `cancelado con el objeto ya subido borra el doc cuando aparece (R3)`() = runTest {
        queued(QueueState.UPLOADING) { it.copy(cancelled = true, uploadedAt = clock.now) }
        remote.exists = Result.success(true)
        assertEquals(EngineResult.RETRY, engine.run("A", "r1"))
        assertEquals(listOf("r1"), recorder.deleted, "el fichero local se borra ya")
        assertEquals(1, queue.items.size, "el elemento espera al doc")
        assertTrue(remote.deletedDocs.isEmpty())
        remote.status.value = RemoteRecordingStatus.READY
        assertEquals(EngineResult.DONE, engine.run("A", "r1"))
        assertEquals(listOf("r1"), remote.deletedDocs)
        assertTrue(queue.items.isEmpty())
    }

    @Test
    fun `cancelado y subido sin doc tras 24 h se abandona y un fallo al borrar el doc reintenta`() = runTest {
        queued(QueueState.UPLOADED) { it.copy(cancelled = true, uploadedAt = clock.now) }
        assertEquals(EngineResult.RETRY, engine.run("A", "r1"))
        remote.status.value = RemoteRecordingStatus.REJECTED
        remote.deleteDocFailure = IOException("red")
        assertEquals(EngineResult.RETRY, engine.run("A", "r1"))
        assertEquals(1, queue.items.size)
        remote.status.value = null
        clock.now += RecordingUploadEngine.CONFIRM_DEADLINE_MS
        assertEquals(EngineResult.DONE, engine.run("A", "r1"))
        assertTrue(queue.items.isEmpty())
    }

    @Test
    fun `cancelado subiendo con la existencia del objeto sin poder comprobar reintenta`() = runTest {
        queued(QueueState.UPLOADING) { it.copy(cancelled = true) }
        remote.exists = Result.failure(IOException("red"))
        assertEquals(EngineResult.RETRY, engine.run("A", "r1"))
        assertEquals(1, queue.items.size)
    }

    @Test
    fun `si se cancela justo al pasar a uploading no se sube y se limpia`() = runTest {
        queued()
        queue.afterUpdate = {
            queue.items[0] = queue.items[0].copy(cancelled = true)
            queue.afterUpdate = null
        }
        assertEquals(EngineResult.DONE, engine.run("A", "r1"))
        assertTrue(remote.requests.isEmpty())
        assertTrue(queue.items.isEmpty())
    }

    // --- cancelacion del worker (leccion 4) ---

    @Test
    fun `cancelar la ejecucion detiene la tarea de Storage desde otro hilo y conserva la cola`() {
        queued()
        val uploadThread = AtomicReference<String?>(null)
        val cancelThread = AtomicReference<String?>(null)
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        remote.uploadBlock = { _, onSession ->
            suspendCancellableCoroutine<UploadResult> { cont ->
                uploadThread.set(Thread.currentThread().name)
                onSession("sess-live")
                cont.invokeOnCancellation {
                    cancelThread.set(Thread.currentThread().name)
                    stopped.countDown()
                }
                started.countDown()
            }
        }
        runBlocking {
            val run = async(Dispatchers.Default) { engine.run("A", "r1") }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            withTimeout(5_000) { run.cancelAndJoin() }
        }
        assertTrue(stopped.await(5, TimeUnit.SECONDS), "la tarea de Storage debe cancelarse")
        assertNotNull(cancelThread.get())
        assertNotEquals(uploadThread.get(), cancelThread.get(), "se cancela desde el hilo del que cancela")
        assertTrue(recorder.deleted.isEmpty())
        assertEquals(QueueState.UPLOADING, only().state)
        assertEquals("sess-live", only().sessionUri)
    }

    @Test
    fun `cancelar mientras espera la confirmacion no pierde el elemento`() {
        queued(QueueState.UPLOADED) { it.copy(uploadedAt = clock.now) }
        val gate = CompletableDeferred<Unit>()
        val blocking = object : RecordingRemoteDataSource by remote {
            override fun observeStatus(uid: String, id: String): Flow<RemoteRecordingStatus?> = flow {
                gate.complete(Unit)
                awaitCancellation()
            }
        }
        runBlocking {
            val run = async(Dispatchers.Default) { RecordingUploadEngine(queue, blocking, recorder, clock).run("A", "r1") }
            withTimeout(5_000) { gate.await() }
            withTimeout(5_000) { run.cancelAndJoin() }
        }
        assertEquals(1, queue.items.size)
        assertTrue(recorder.deleted.isEmpty())
    }
}
