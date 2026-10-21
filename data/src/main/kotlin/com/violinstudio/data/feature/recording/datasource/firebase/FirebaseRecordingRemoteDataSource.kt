package com.violinstudio.data.feature.recording.datasource.firebase

import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.UploadTask
import com.violinstudio.data.feature.recording.datasource.ConsentSnapshot
import com.violinstudio.data.feature.recording.datasource.RecordingRemoteDataSource
import com.violinstudio.data.feature.recording.datasource.RemoteRecordingStatus
import com.violinstudio.data.feature.recording.datasource.UploadRequest
import com.violinstudio.data.feature.recording.datasource.UploadResult
import com.violinstudio.data.feature.recording.utils.StorageErrorTranslator
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await

private const val USERS = "users"
private const val RECORDINGS = "recordings"

/**
 * Adaptador fino sobre Auth, Storage y Firestore, sin logica de negocio (no se mide en la cobertura; se verifica en el
 * E2E/dispositivo). La subida usa `putFile(uri, metadata, sessionUri)`: reanudable y con el metadato del contrato de
 * reglas. Cancelar la corrutina cancela la `UploadTask` desde el hilo de quien cancela (leccion 4).
 */
class FirebaseRecordingRemoteDataSource @Inject constructor(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore,
    private val storage: FirebaseStorage
) : RecordingRemoteDataSource {
    override fun currentUid(): String? = auth.currentUser?.uid

    override suspend fun upload(request: UploadRequest, onSession: (String) -> Unit): UploadResult = try {
        val metadata = StorageMetadata.Builder().apply {
            setContentType(request.contentType)
            request.customMetadata.forEach { (key, value) -> setCustomMetadata(key, value) }
        }.build()
        val task = storage.reference.child(request.objectPath).putFile(
            Uri.fromFile(File(request.filePath)),
            metadata,
            request.sessionUri?.let(Uri::parse)
        )
        task.addOnProgressListener { snapshot -> snapshot.uploadSessionUri?.toString()?.let(onSession) }
        awaitUpload(task)
        UploadResult.Uploaded
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (StorageErrorTranslator.isCanceled(e)) throw CancellationException("subida cancelada")
        UploadResult.Failed(StorageErrorTranslator.translate(e))
    }

    private suspend fun awaitUpload(task: UploadTask) = suspendCancellableCoroutine { continuation ->
        task.addOnSuccessListener { continuation.resume(Unit) }
            .addOnFailureListener { continuation.resumeWithException(it) }
        // Se ejecuta en el hilo que cancela, no en el de la subida.
        continuation.invokeOnCancellation { task.cancel() }
    }

    override suspend fun objectExists(objectPath: String): Result<Boolean> = try {
        storage.reference.child(objectPath).metadata.await()
        Result.success(true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: StorageException) {
        if (e.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND) Result.success(false) else Result.failure(e)
    } catch (e: Exception) {
        Result.failure(e)
    }

    override fun observeStatus(uid: String, id: String): Flow<RemoteRecordingStatus?> = callbackFlow {
        val registration = db.collection(USERS).document(uid).collection(RECORDINGS).document(id)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                } else {
                    trySend(
                        when (snapshot?.getString("status")) {
                            "ready" -> RemoteRecordingStatus.READY
                            "rejected" -> RemoteRecordingStatus.REJECTED
                            else -> null
                        }
                    )
                }
            }
        awaitClose { registration.remove() }
    }

    override suspend fun deleteDoc(uid: String, id: String): Result<Unit> = guarded {
        db.collection(USERS).document(uid).collection(RECORDINGS).document(id).delete().await()
        Unit
    }

    override suspend fun consent(uid: String): Result<ConsentSnapshot> = guarded {
        val user = db.collection(USERS).document(uid).get(Source.SERVER).await()
        ConsentSnapshot(
            granted = user.getString("recordingConsent") == "granted",
            epoch = user.getLong("recordingConsentEpoch") ?: 0L
        )
    }

    private inline fun <T> guarded(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
