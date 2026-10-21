package com.violinstudio.data.feature.recording.datasource.firebase

import com.google.firebase.storage.FirebaseStorage
import com.violinstudio.data.feature.recording.datasource.media.RecordingBytesSource
import com.violinstudio.domain.feature.recording.model.RecordingLimits
import javax.inject.Inject
import kotlinx.coroutines.tasks.await

/** Descarga a memoria (tope: el maximo que admiten las reglas); las reglas deciden quien puede leer el objeto. */
class FirebaseRecordingBytesSource @Inject constructor(private val storage: FirebaseStorage) : RecordingBytesSource {
    override suspend fun read(objectPath: String): ByteArray =
        storage.reference.child(objectPath).getBytes(RecordingLimits.MAX_AUDIO_BYTES).await()
}
