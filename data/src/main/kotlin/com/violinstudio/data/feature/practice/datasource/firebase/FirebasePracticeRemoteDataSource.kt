package com.violinstudio.data.feature.practice.datasource.firebase

import com.google.firebase.firestore.AggregateField
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.violinstudio.data.feature.practice.datasource.PracticeDoc
import com.violinstudio.data.feature.practice.datasource.PracticeRemoteDataSource
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

private const val USERS = "users"
private const val SESSIONS = "practiceSessions"
private const val TOTALS_TIMEOUT_MS = 10_000L

/**
 * Adaptador fino sobre Firestore, sin lógica; se prueba en el E2E. Las escrituras no esperan al servidor y se ignora
 * su fallo diferido: el SDK retira la escritura rechazada de la caché y el historial se actualiza solo.
 */
class FirebasePracticeRemoteDataSource @Inject constructor(private val db: FirebaseFirestore) :
    PracticeRemoteDataSource {
    private fun sessions(uid: String) = db.collection(USERS).document(uid).collection(SESSIONS)

    private fun doc(uid: String, id: String): DocumentReference = sessions(uid).document(id)

    override fun observe(uid: String, limit: Int): Flow<List<PracticeDoc>> = callbackFlow {
        val query = sessions(uid).orderBy("startedAt", Query.Direction.DESCENDING).limit(limit.toLong())
        val registration = query.addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            if (error != null) {
                close(error)
            } else if (snapshot != null) {
                trySend(
                    snapshot.documents.map { PracticeDoc(it.id, it.data.orEmpty(), it.metadata.hasPendingWrites()) }
                )
            }
        }
        awaitClose { registration.remove() }
    }

    override suspend fun totals(uid: String): Pair<Long, Long> {
        val duration = AggregateField.sum("durationSec")
        // Sin conexion la tarea no falla enseguida: se acota y se lanza como cualquier otro fallo.
        val snapshot = withTimeoutOrNull(TOTALS_TIMEOUT_MS) {
            sessions(uid).aggregate(AggregateField.count(), duration).get(AggregateSource.SERVER).await()
        } ?: throw IllegalStateException("totals timeout")
        return snapshot.count to (snapshot.getLong(duration) ?: 0L)
    }

    override suspend fun exists(uid: String, id: String): Boolean = try {
        doc(uid, id).get(Source.CACHE).await().exists()
    } catch (_: FirebaseFirestoreException) {
        false
    }

    override fun create(uid: String, id: String, fields: Map<String, Any>) {
        doc(uid, id).set(fields)
    }

    override fun update(uid: String, id: String, fields: Map<String, Any>) {
        doc(uid, id).update(fields)
    }

    override fun delete(uid: String, id: String) {
        doc(uid, id).delete()
    }
}
