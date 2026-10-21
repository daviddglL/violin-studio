package com.violinstudio.data.feature.recording.datasource.firebase

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.violinstudio.data.feature.recording.utils.FinalizeStatusParser
import com.violinstudio.data.feature.recording.utils.RecordingAccountParser
import com.violinstudio.domain.feature.recording.model.FinalizeStatus
import com.violinstudio.domain.feature.recording.model.RecordingAccount
import com.violinstudio.domain.feature.recording.repository.FinalizeStatusSource
import com.violinstudio.domain.feature.recording.repository.RecordingAccountSource
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.tasks.await

private const val USERS = "users"
private const val RECORDINGS = "recordings"

/**
 * Adaptadores finos sobre Firestore (la traduccion vive en los parsers). Leen SIEMPRE del servidor: decidir con la
 * cache podria abrir el micro con un consentimiento ya revocado o dar por pendiente una toma ya finalizada.
 */
class FirebaseRecordingAccountSource @Inject constructor(private val db: FirebaseFirestore) : RecordingAccountSource {
    override suspend fun current(uid: String): Result<RecordingAccount> = serverRead {
        val snapshot = db.collection(USERS).document(uid).get(Source.SERVER).await()
        RecordingAccountParser.parse(snapshot.data ?: throw NoSuchElementException("Sin perfil"))
    }
}

class FirebaseFinalizeStatusSource @Inject constructor(private val db: FirebaseFirestore) : FinalizeStatusSource {
    override suspend fun status(uid: String, id: String): Result<FinalizeStatus> = serverRead {
        val snapshot = db.collection(USERS).document(uid).collection(RECORDINGS).document(id).get(Source.SERVER).await()
        FinalizeStatusParser.parse(snapshot.exists(), snapshot.data)
    }
}

private inline fun <T> serverRead(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
