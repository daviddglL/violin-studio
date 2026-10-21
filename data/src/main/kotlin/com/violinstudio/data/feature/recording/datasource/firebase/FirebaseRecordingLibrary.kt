package com.violinstudio.data.feature.recording.datasource.firebase

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.violinstudio.data.commons.firebase.observeDocs
import com.violinstudio.data.feature.recording.utils.RecordingSummaryParser
import com.violinstudio.domain.feature.recording.model.RecordingSummary
import com.violinstudio.domain.feature.recording.repository.RecordingLibrary
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/**
 * Adaptador fino sobre `users/{uid}/recordings` (el dueno lee y borra sin filtros, reglas A12); se prueba en el E2E.
 * Borrar espera al servidor: el usuario debe saber si de verdad se borro.
 */
class FirebaseRecordingLibrary @Inject constructor(private val db: FirebaseFirestore) : RecordingLibrary {
    private fun recordings(uid: String) = db.collection("users").document(uid).collection("recordings")

    override fun observe(uid: String): Flow<List<RecordingSummary>> =
        recordings(uid).orderBy("createdAt", Query.Direction.DESCENDING).observeDocs()
            .map { docs -> docs.mapNotNull { RecordingSummaryParser.parse(it.id, it.data) } }

    override suspend fun delete(uid: String, id: String): Result<Unit> = try {
        recordings(uid).document(id).delete().await()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
