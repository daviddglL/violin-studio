package com.violinstudio.domain.feature.recording.usecase

import com.violinstudio.domain.feature.profile.model.Role
import com.violinstudio.domain.feature.recording.model.FailReason
import com.violinstudio.domain.feature.recording.model.FinalizeStatus
import com.violinstudio.domain.feature.recording.model.QueueState
import com.violinstudio.domain.feature.recording.model.QueuedRecording
import com.violinstudio.domain.feature.recording.model.RecordedTake
import com.violinstudio.domain.feature.recording.model.RecordingAccount
import com.violinstudio.domain.feature.recording.model.RecordingSpec
import com.violinstudio.domain.feature.recording.model.UploadError
import com.violinstudio.domain.feature.recording.model.UploadFailure
import com.violinstudio.domain.feature.recording.model.UploadPass
import com.violinstudio.domain.feature.recording.repository.FinalizeStatusSource
import com.violinstudio.domain.feature.recording.repository.Recorder
import com.violinstudio.domain.feature.recording.repository.RecordingAccountSource
import com.violinstudio.domain.feature.recording.repository.RecordingHandle
import com.violinstudio.domain.feature.recording.repository.RecordingUploader
import com.violinstudio.domain.testing.FakeRecordingQueue
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** REQ-REC-04 / REQ-REC-14 / R1: maquina de estados de la cola de subida. */
class ProcessUploadQueueUseCaseTest {
    private class FakeUploader : RecordingUploader {
        val uploaded = mutableListOf<QueuedRecording>()
        var results = ArrayDeque<Result<Unit>>()
        var session: String? = null
        var exists: Result<Boolean> = Result.success(false)

        override suspend fun upload(item: QueuedRecording, onSession: suspend (String) -> Unit): Result<Unit> {
            uploaded += item
            session?.let { onSession(it) }
            return results.removeFirstOrNull() ?: Result.success(Unit)
        }

        override suspend fun exists(item: QueuedRecording): Result<Boolean> = exists
    }

    private class FakeFinalize(var status: Result<FinalizeStatus> = Result.success(FinalizeStatus.Pending)) :
        FinalizeStatusSource {
        override suspend fun status(uid: String, id: String): Result<FinalizeStatus> = status
    }

    private class FakeAccounts(var result: Result<RecordingAccount>) : RecordingAccountSource {
        override suspend fun current(uid: String): Result<RecordingAccount> = result
    }

    private class FakeRecorder : Recorder {
        val deleted = mutableListOf<String>()
        var fail = false

        override suspend fun start(spec: RecordingSpec): Result<RecordingHandle> =
            Result.failure(IllegalStateException())

        override suspend fun deleteTake(take: RecordedTake) {
            if (fail) throw IOException("disco")
            deleted += take.id
        }
    }

    private val account = RecordingAccount(
        role = Role.STUDENT,
        isMinor = false,
        baseConsentGranted = true,
        recordingConsentGranted = true,
        recordingConsentVersion = 1,
        consentEpoch = 3,
        liveCount = 2
    )

    private fun item(id: String, state: QueueState = QueueState.RECORDED, session: String? = null) = QueuedRecording(
        RecordedTake(id, "A", "/x/$id.m4a", 5_000, 1_000, consentEpoch = 3),
        state = state,
        sessionUri = session
    )

    private val queue = FakeRecordingQueue()
    private val uploader = FakeUploader()
    private val finalize = FakeFinalize()
    private val accounts = FakeAccounts(Result.success(account))
    private val recorder = FakeRecorder()
    private val process = ProcessUploadQueueUseCase(queue, uploader, finalize, accounts, recorder)

    private fun unauthorized() = Result.failure<Unit>(UploadFailure(UploadError.Unauthorized(consentRevoked = false)))

    @Test
    fun `una cola vacia o solo con fallidas esta ociosa`() = runTest {
        assertEquals(UploadPass.IDLE, process("A"))
        queue.items.value = listOf(item("r1", QueueState.FAILED))
        assertEquals(UploadPass.IDLE, process("A"))
        assertTrue(uploader.uploaded.isEmpty())
    }

    @Test
    fun `sube, cuenta el intento y espera a finalize con la toma subida`() = runTest {
        queue.items.value = listOf(item("r1"))
        assertEquals(UploadPass.PROGRESSED, process("A"))
        assertEquals(QueueState.UPLOADING, uploader.uploaded.single().state)
        assertEquals(1, uploader.uploaded.single().attempts)
        assertEquals(QueueState.UPLOADED, queue.get("r1")!!.state)
        assertTrue(recorder.deleted.isEmpty())
    }

    @Test
    fun `guarda la sesion de subida mientras sube y la olvida al terminar`() = runTest {
        queue.items.value = listOf(item("r1"))
        uploader.session = "https://s/1"
        uploader.results += Result.failure(UploadFailure(UploadError.Network))
        assertEquals(UploadPass.STALLED, process("A"))
        assertEquals("https://s/1", queue.get("r1")!!.sessionUri)
        assertEquals(QueueState.UPLOADING, queue.get("r1")!!.state)
        process("A")
        assertEquals("https://s/1", uploader.uploaded.last().sessionUri)
        assertNull(queue.get("r1")!!.sessionUri)
    }

    @Test
    fun `finalize ready borra la copia local y saca la toma de la cola`() = runTest {
        queue.items.value = listOf(item("r1"))
        finalize.status = Result.success(FinalizeStatus.Ready)
        assertEquals(UploadPass.IDLE, process("A"))
        assertEquals(listOf("r1"), recorder.deleted)
        assertNull(queue.get("r1"))
    }

    @Test
    fun `una subida ya hecha se comprueba en pasadas siguientes sin volver a subir`() = runTest {
        queue.items.value = listOf(item("r1", QueueState.UPLOADED))
        assertEquals(UploadPass.STALLED, process("A"))
        finalize.status = Result.failure(IOException())
        assertEquals(UploadPass.STALLED, process("A"))
        finalize.status = Result.success(FinalizeStatus.Ready)
        assertEquals(UploadPass.IDLE, process("A"))
        assertTrue(uploader.uploaded.isEmpty())
    }

    @Test
    fun `un done pendiente de limpiar se limpia aunque el fichero no se pueda borrar`() = runTest {
        queue.items.value = listOf(item("r1", QueueState.DONE))
        recorder.fail = true
        assertEquals(UploadPass.IDLE, process("A"))
        assertNull(queue.get("r1"))
    }

    @Test
    fun `finalize rechazado deja la toma fallida con su motivo y la de consentimiento se borra`() = runTest {
        queue.items.value = listOf(item("r1", QueueState.UPLOADED), item("r2", QueueState.UPLOADED))
        finalize.status = Result.success(FinalizeStatus.Rejected(FailReason.SIZE))
        process("A")
        assertEquals(QueueState.FAILED, queue.get("r1")!!.state)
        assertEquals(FailReason.SIZE, queue.get("r1")!!.lastError)
        queue.items.value = listOf(item("r3", QueueState.UPLOADED))
        finalize.status = Result.success(FinalizeStatus.ConsentRejected)
        assertEquals(UploadPass.IDLE, process("A"))
        assertEquals(listOf("r3"), recorder.deleted)
        assertNull(queue.get("r3"))
    }

    @Test
    fun `un fallo transitorio corta la pasada y deja el resto para despues`() = runTest {
        queue.items.value = listOf(item("r1"), item("r2"))
        uploader.results += Result.failure(UploadFailure(UploadError.Http(503)))
        assertEquals(UploadPass.STALLED, process("A"))
        assertEquals(listOf("r1"), uploader.uploaded.map { it.id })
        assertEquals(QueueState.RECORDED, queue.get("r2")!!.state)
    }

    @Test
    fun `un fallo permanente marca la toma fallida y sigue con la siguiente`() = runTest {
        queue.items.value = listOf(item("r1"), item("r2"))
        uploader.results += Result.failure(UploadFailure(UploadError.TooLarge))
        process("A")
        assertEquals(QueueState.FAILED, queue.get("r1")!!.state)
        assertEquals(FailReason.SIZE, queue.get("r1")!!.lastError)
        assertEquals(QueueState.UPLOADED, queue.get("r2")!!.state)
        assertEquals(listOf<String>(), recorder.deleted)
    }

    @Test
    fun `un fallo desconocido del uploader cuenta como permanente`() = runTest {
        queue.items.value = listOf(item("r1"))
        uploader.results += Result.failure(IllegalStateException())
        process("A")
        assertEquals(FailReason.UNKNOWN, queue.get("r1")!!.lastError)
    }

    @Test
    fun `con una sesion guardada el fallo permanente reintenta una vez desde cero`() = runTest {
        queue.items.value = listOf(item("r1", QueueState.UPLOADING, session = "https://s/old"))
        uploader.results += Result.failure(UploadFailure(UploadError.Http(410)))
        assertEquals(UploadPass.PROGRESSED, process("A"))
        assertNull(queue.get("r1")!!.sessionUri)
        assertEquals(QueueState.UPLOADING, queue.get("r1")!!.state)
        uploader.results += Result.failure(UploadFailure(UploadError.Http(410)))
        process("A")
        assertEquals(QueueState.FAILED, queue.get("r1")!!.state)
    }

    @Test
    fun `un 403 con el objeto ya en Storage es una subida hecha`() = runTest {
        queue.items.value = listOf(item("r1"))
        uploader.results += unauthorized()
        uploader.exists = Result.success(true)
        process("A")
        assertEquals(QueueState.UPLOADED, queue.get("r1")!!.state)
    }

    @Test
    fun `un 403 con el consentimiento revocado o la epoca caducada borra la toma (R1)`() = runTest {
        queue.items.value = listOf(item("r1"))
        uploader.results += unauthorized()
        accounts.result = Result.success(account.copy(recordingConsentGranted = false))
        assertEquals(UploadPass.IDLE, process("A"))
        assertEquals(listOf("r1"), recorder.deleted)

        queue.items.value = listOf(item("r2"))
        uploader.results += unauthorized()
        accounts.result = Result.success(account.copy(consentEpoch = 4))
        process("A")
        assertEquals(listOf("r1", "r2"), recorder.deleted)
        assertNull(queue.get("r2"))
    }

    @Test
    fun `un 403 con el cupo lleno es fallo de cupo y otro 403 es de permisos`() = runTest {
        queue.items.value = listOf(item("r1"), item("r2"))
        uploader.results += unauthorized()
        accounts.result = Result.success(account.copy(liveCount = 20))
        process("A")
        assertEquals(FailReason.QUOTA, queue.get("r1")!!.lastError)
        uploader.results += unauthorized()
        accounts.result = Result.success(account)
        queue.items.value = listOf(item("r3"))
        process("A")
        assertEquals(FailReason.PERMISSION, queue.get("r3")!!.lastError)
        assertTrue(recorder.deleted.isEmpty())
    }

    @Test
    fun `un 403 que no se puede aclarar se reintenta sin borrar nada`() = runTest {
        queue.items.value = listOf(item("r1"))
        uploader.results += unauthorized()
        uploader.exists = Result.failure(IOException())
        assertEquals(UploadPass.STALLED, process("A"))
        uploader.results += unauthorized()
        uploader.exists = Result.success(false)
        accounts.result = Result.failure(IOException())
        assertEquals(UploadPass.STALLED, process("A"))
        assertEquals(QueueState.UPLOADING, queue.get("r1")!!.state)
        assertTrue(recorder.deleted.isEmpty())
    }

    @Test
    fun `si la cola no se puede escribir no sube nada`() = runTest {
        queue.items.value = listOf(item("r1"))
        queue.failWrites = true
        assertEquals(UploadPass.STALLED, process("A"))
        assertTrue(uploader.uploaded.isEmpty())
    }

    @Test
    fun `solo procesa la cola del uid pedido`() = runTest {
        queue.items.value = listOf(item("r1").let { it.copy(take = it.take.copy(uid = "B")) })
        assertEquals(UploadPass.IDLE, process("A"))
        assertTrue(uploader.uploaded.isEmpty())
    }
}
