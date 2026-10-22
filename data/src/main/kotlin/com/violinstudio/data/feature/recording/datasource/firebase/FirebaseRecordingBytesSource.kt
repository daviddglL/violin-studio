package com.violinstudio.data.feature.recording.datasource.firebase

import com.google.firebase.storage.FirebaseStorage
import com.violinstudio.data.feature.recording.datasource.media.RecordingBytesSource
import javax.inject.Inject
import kotlinx.coroutines.tasks.await

/** Descarga a memoria hasta [maxBytes] (el maximo que admiten las reglas); las reglas deciden quien puede leerlo. */
class FirebaseRecordingBytesSource @Inject constructor(private val storage: FirebaseStorage) : RecordingBytesSource {
    override suspend fun read(objectPath: String, maxBytes: Long): ByteArray =
        storage.reference.child(objectPath).getBytes(maxBytes).await()
}
