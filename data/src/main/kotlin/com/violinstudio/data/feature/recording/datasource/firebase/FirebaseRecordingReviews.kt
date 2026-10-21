package com.violinstudio.data.feature.recording.datasource.firebase

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.violinstudio.data.commons.firebase.observeDocs
import com.violinstudio.data.feature.recording.utils.RecordingSummaryParser
import com.violinstudio.data.feature.recording.utils.ReviewParser
import com.violinstudio.domain.feature.recording.model.RecordingSummary
import com.violinstudio.domain.feature.recording.model.Review
import com.violinstudio.domain.feature.recording.model.Verdict
import com.violinstudio.domain.feature.recording.repository.RecordingReviews
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/**
 * Adaptador fino sobre Firestore; las reglas mandan (REQ-SEC-R02) y se prueban en `rules-tests`. La consulta del
 * profesor filtra `status == ready` (las reglas lo exigen) y usa el indice `recordings(status, createdAt)`.
 * Crear fija `createdAt` y `updatedAt` con la hora del servidor; actualizar solo toca veredicto, texto y `updatedAt`.
 */
class FirebaseRecordingReviews @Inject constructor(private val db: FirebaseFirestore) : RecordingReviews {
    private fun recordings(uid: String) = db.collection("users").document(uid).collection("recordings")

    private fun reviews(uid: String, recordingId: String) = recordings(uid).document(recordingId).collection("reviews")

    override fun observeStudentRecordings(studentUid: String): Flow<List<RecordingSummary>> =
        recordings(studentUid).whereEqualTo("status", "ready").orderBy("createdAt", Query.Direction.DESCENDING)
            .observeDocs()
            .map { docs -> docs.mapNotNull { RecordingSummaryParser.parse(it.id, it.data) } }

    override fun observeReview(ownerUid: String, recordingId: String, reviewerUid: String): Flow<Review?> =
        callbackFlow {
            val registration = reviews(ownerUid, recordingId).document(reviewerUid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) close(error) else trySend(ReviewParser.parse(snapshot?.data))
                }
            awaitClose { registration.remove() }
        }

    override fun observeReviews(ownerUid: String, recordingId: String): Flow<List<Review>> =
        reviews(ownerUid, recordingId).observeDocs().map { docs -> docs.mapNotNull { ReviewParser.parse(it.data) } }

    override suspend fun save(
        ownerUid: String,
        recordingId: String,
        reviewerUid: String,
        verdict: Verdict,
        text: String
    ): Result<Unit> = try {
        val ref = reviews(ownerUid, recordingId).document(reviewerUid)
        // Las reglas distinguen crear de actualizar (campos y marcas de tiempo distintos): se mira en el servidor.
        if (ref.get(Source.SERVER).await().exists()) {
            ref.update(mapOf("verdict" to verdict.wire, "text" to text, "updatedAt" to FieldValue.serverTimestamp()))
                .await()
        } else {
            val now = FieldValue.serverTimestamp()
            ref.set(
                mapOf(
                    "reviewerUid" to reviewerUid,
                    "verdict" to verdict.wire,
                    "text" to text,
                    "createdAt" to now,
                    "updatedAt" to now
                )
            ).await()
        }
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
