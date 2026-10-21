package com.violinstudio.data.feature.recording.datasource.work

import android.app.Application
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.violinstudio.data.feature.recording.datasource.ConsentSnapshot
import com.violinstudio.data.feature.recording.datasource.RecordingRemoteDataSource
import com.violinstudio.data.feature.recording.datasource.RemoteRecordingStatus
import com.violinstudio.data.feature.recording.datasource.UploadRequest
import com.violinstudio.data.feature.recording.datasource.UploadResult
import com.violinstudio.domain.feature.recording.model.QueuedRecording
import com.violinstudio.domain.feature.recording.model.RecordedTake
import com.violinstudio.domain.feature.recording.model.RecordingSpec
import com.violinstudio.domain.feature.recording.repository.Recorder
import com.violinstudio.domain.feature.recording.repository.RecordingHandle
import com.violinstudio.domain.feature.recording.repository.RecordingQueue
import java.io.File
import java.time.Clock
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** B7b.1: `TestListenableWorkerBuilder` + planificador con `WorkManagerTestInitHelper` (Robolectric SDK 34). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RecordingUploadWorkerTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private class OneItemQueue(var item: QueuedRecording?) : RecordingQueue {
        override suspend fun enqueue(item: QueuedRecording) = Result.success(Unit)

        override fun observe(uid: String): Flow<List<QueuedRecording>> = flowOf(listOfNotNull(item))

        override suspend fun update(uid: String, id: String, transform: (QueuedRecording) -> QueuedRecording) =
            Result.success(item?.let(transform)?.also { item = it })

        override suspend fun remove(uid: String, id: String): Result<Unit> = Result.success(Unit).also { item = null }

        override suspend fun clear(uid: String) = Result.success(emptyList<QueuedRecording>())
    }

    private class IdleRecorder : Recorder {
        override suspend fun start(spec: RecordingSpec): Result<RecordingHandle> =
            Result.failure(IllegalStateException())

        override suspend fun deleteTake(take: RecordedTake) = Unit
    }

    private class StubRemote(var uid: String?, var result: UploadResult = UploadResult.Uploaded) :
        RecordingRemoteDataSource {
        override fun currentUid() = uid

        override suspend fun upload(request: UploadRequest, onSession: (String) -> Unit) = result

        override suspend fun objectExists(objectPath: String) = Result.success(false)

        override fun observeStatus(uid: String, id: String): Flow<RemoteRecordingStatus?> =
            flowOf(RemoteRecordingStatus.READY)

        override suspend fun deleteDoc(uid: String, id: String) = Result.success(Unit)

        override suspend fun consent(uid: String) = Result.success(ConsentSnapshot(true, 0))

        override suspend fun refreshAuth() = Result.success(Unit)
    }

    private fun worker(remote: StubRemote, queue: OneItemQueue, uid: String?, id: String?): RecordingUploadWorker {
        val engine = RecordingUploadEngine(queue, remote, IdleRecorder(), Clock.systemUTC())
        val factory = object : WorkerFactory() {
            override fun createWorker(c: Context, name: String, params: WorkerParameters): ListenableWorker =
                RecordingUploadWorker(c, params, engine)
        }
        return TestListenableWorkerBuilder<RecordingUploadWorker>(context)
            .setInputData(RecordingUploadWorker.inputData(uid, id))
            .setWorkerFactory(factory)
            .build()
    }

    private fun item(): QueuedRecording {
        val file = tmp.newFile("r1.m4a").also { it.writeBytes(ByteArray(8)) }
        return QueuedRecording(RecordedTake("r1", "A", file.path, 1_000, 8, 0))
    }

    @Test
    fun `una subida confirmada termina en success`() = runBlocking<Unit> {
        val queue = OneItemQueue(item())
        assertEquals(ListenableWorker.Result.success(), worker(StubRemote("A"), queue, "A", "r1").doWork())
        assertEquals(null, queue.item)
    }

    @Test
    fun `con otro usuario reintenta y un fallo permanente es failure`() = runBlocking<Unit> {
        val queue = OneItemQueue(item())
        assertEquals(ListenableWorker.Result.retry(), worker(StubRemote("B"), queue, "A", "r1").doWork())
        val quota = StubRemote("A", UploadResult.Failed(com.violinstudio.domain.feature.recording.model.UploadError.QuotaExceeded))
        assertEquals(ListenableWorker.Result.failure(), worker(quota, queue, "A", "r1").doWork())
    }

    @Test
    fun `sin uid o id en la entrada falla sin tocar nada`() = runBlocking<Unit> {
        val queue = OneItemQueue(item())
        assertEquals(ListenableWorker.Result.failure(), worker(StubRemote("A"), queue, null, "r1").doWork())
        assertEquals(ListenableWorker.Result.failure(), worker(StubRemote("A"), queue, "A", null).doWork())
        assertTrue(queue.item != null)
    }

    @Test
    fun `el trabajo es unico por grabacion con KEEP conectado y backoff exponencial de 30 s`() {
        val request = WorkManagerRecordingUploadScheduler.request("A", "r1")
        assertEquals("rec-upload-r1", WorkManagerRecordingUploadScheduler.uniqueName("r1"))
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(TimeUnit.SECONDS.toMillis(30), request.workSpec.backoffDelayDuration)
        assertEquals(androidx.work.ExistingWorkPolicy.KEEP, WorkManagerRecordingUploadScheduler.POLICY)
        assertEquals("A", request.workSpec.input.getString(RecordingUploadWorker.KEY_UID))
        assertEquals("r1", request.workSpec.input.getString(RecordingUploadWorker.KEY_ID))
    }

    @Test
    fun `programar dos veces el mismo id no duplica y cancelar lo detiene`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val scheduler = WorkManagerRecordingUploadScheduler(context)
        scheduler.schedule("A", "r1")
        scheduler.schedule("A", "r1")
        val manager = WorkManager.getInstance(context)
        assertEquals(1, manager.getWorkInfosForUniqueWork("rec-upload-r1").get().size)
        scheduler.cancel("r1")
        assertEquals(
            androidx.work.WorkInfo.State.CANCELLED,
            manager.getWorkInfosForUniqueWork("rec-upload-r1").get().single().state
        )
    }
}
