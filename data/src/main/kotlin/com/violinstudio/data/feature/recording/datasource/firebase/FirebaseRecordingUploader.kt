package com.violinstudio.data.feature.recording.datasource.firebase

import android.net.Uri
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.UploadTask
import com.violinstudio.data.feature.recording.utils.StorageErrorMapper
import com.violinstudio.domain.feature.recording.model.QueuedRecording
import com.violinstudio.domain.feature.recording.model.RecordingUploadContract
import com.violinstudio.domain.feature.recording.model.UploadError
import com.violinstudio.domain.feature.recording.model.UploadFailure
import com.violinstudio.domain.feature.recording.repository.RecordingUploader
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Adaptador fino sobre Firebase Storage; la logica (contrato, clasificacion, estados) vive en domain y en
 * [StorageErrorMapper]. Se prueba en el E2E. Ni rutas ni ids salen en logs ni en excepciones.
 */
class FirebaseRecordingUploader @Inject constructor(private val storage: FirebaseStorage) : RecordingUploader {
    override suspend fun upload(item: QueuedRecording, onSession: suspend (String) -> Unit): Result<Unit> = guard {
        val metadata = StorageMetadata.Builder().apply {
            contentType = RecordingUploadContract.CONTENT_TYPE
            RecordingUploadContract.customMetadata(item).forEach { (key, value) -> setCustomMetadata(key, value) }
        }.build()
        val ref = storage.reference.child(RecordingUploadContract.objectPath(item))
        val file = Uri.fromFile(File(item.take.path))
        val task = item.sessionUri?.let { ref.putFile(file, metadata, Uri.parse(it)) } ?: ref.putFile(file, metadata)
        coroutineScope {
            // El listener corre en el hilo principal: la URI viaja por un canal y se guarda desde una corrutina.
            val sessions = Channel<String>(Channel.CONFLATED)
            val saver = launch {
                var last: String? = item.sessionUri
                for (uri in sessions) {
                    if (uri != last) {
                        onSession(uri)
                        last = uri
                    }
                }
            }
            task.addOnProgressListener { snapshot ->
                snapshot.uploadSessionUri?.let { sessions.trySend(it.toString()) }
            }
            try {
                task.awaitCancellable()
            } finally {
                sessions.close()
            }
            saver.join()
        }
    }

    override suspend fun exists(item: QueuedRecording): Result<Boolean> = try {
        storage.reference.child(RecordingUploadContract.objectPath(item)).metadata.await()
        Result.success(true)
    } catch (e: StorageException) {
        if (e.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND) Result.success(false) else Result.failure(e)
    }

    private suspend fun UploadTask.awaitCancellable() {
        try {
            await()
        } catch (e: CancellationException) {
            cancel()
            throw e
        }
    }

    private inline fun guard(block: () -> Unit): Result<Unit> = try {
        block()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: StorageException) {
        Result.failure(UploadFailure(StorageErrorMapper.map(e.errorCode, e.httpResultCode, e.cause is IOException)))
    } catch (_: IOException) {
        Result.failure(UploadFailure(UploadError.Network))
    } catch (_: IllegalArgumentException) {
        // Metadatos fuera del contrato: nunca subiran.
        Result.failure(UploadFailure(UploadError.Other))
    }
}
