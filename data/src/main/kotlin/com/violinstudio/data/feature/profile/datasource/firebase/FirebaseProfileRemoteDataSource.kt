package com.violinstudio.data.feature.profile.datasource.firebase

import com.google.firebase.firestore.FirebaseFirestore
import com.violinstudio.data.feature.profile.datasource.ProfileRemoteDataSource
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

private const val USERS = "users"

/** Adaptador fino sobre Firestore, sin lógica; se prueba en el E2E (8b). */
class FirebaseProfileRemoteDataSource @Inject constructor(private val db: FirebaseFirestore) :
    ProfileRemoteDataSource {
    override fun observe(uid: String): Flow<Map<String, Any?>?> = callbackFlow {
        val registration = db.collection(USERS).document(uid).addSnapshotListener { snapshot, error ->
            if (error != null) close(error) else trySend(snapshot?.data)
        }
        awaitClose { registration.remove() }
    }

    override suspend fun update(uid: String, fields: Map<String, Any>) {
        db.collection(USERS).document(uid).update(fields).await()
    }
}
