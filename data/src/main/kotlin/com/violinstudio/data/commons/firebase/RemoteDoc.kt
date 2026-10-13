package com.violinstudio.data.commons.firebase

import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Doc crudo de Firestore; [hasPendingWrites] viene de los metadatos del snapshot. */
data class RemoteDoc(val id: String, val data: Map<String, Any?>, val hasPendingWrites: Boolean)

/** Listener con `MetadataChanges.INCLUDE` (para ver cuando se confirma una escritura); el flujo falla si el listener falla. */
fun Query.observeDocs(): Flow<List<RemoteDoc>> = callbackFlow {
    val registration = addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
        if (error != null) {
            close(error)
        } else if (snapshot != null) {
            trySend(snapshot.documents.map { RemoteDoc(it.id, it.data.orEmpty(), it.metadata.hasPendingWrites()) })
        }
    }
    awaitClose { registration.remove() }
}
