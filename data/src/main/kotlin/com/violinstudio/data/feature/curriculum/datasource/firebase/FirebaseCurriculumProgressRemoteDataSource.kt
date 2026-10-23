package com.violinstudio.data.feature.curriculum.datasource.firebase

import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.violinstudio.data.feature.curriculum.datasource.CurriculumProgressRemoteDataSource
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

private const val USERS = "users"
private const val PROGRESS = "progress"
private const val CURRICULUM = "curriculum"

/**
 * Adaptador fino sobre Firestore, sin logica; se prueba en el E2E. Escribe con `arrayUnion`/`arrayRemove` y `merge`, sin
 * leer antes: funciona sin conexion y dos dispositivos no se pisan la lista. Si el servidor rechaza una escritura, el
 * SDK la retira de la cache y el progreso se actualiza solo.
 */
class FirebaseCurriculumProgressRemoteDataSource @Inject constructor(private val db: FirebaseFirestore) :
    CurriculumProgressRemoteDataSource {
    private fun doc(uid: String) = db.collection(USERS).document(uid).collection(PROGRESS).document(CURRICULUM)

    override fun observe(uid: String): Flow<List<String>> = callbackFlow {
        val registration = doc(uid).addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
            } else if (snapshot != null) {
                trySend((snapshot.get("completed") as? List<*>)?.filterIsInstance<String>().orEmpty())
            }
        }
        awaitClose { registration.remove() }
    }

    override fun add(uid: String, ids: Collection<String>) {
        write(uid, FieldValue.arrayUnion(*ids.toTypedArray()))
    }

    override suspend fun addAndAwaitServer(uid: String, ids: Collection<String>) {
        write(uid, FieldValue.arrayUnion(*ids.toTypedArray())).await()
    }

    override fun remove(uid: String, id: String) {
        write(uid, FieldValue.arrayRemove(id))
    }

    private fun write(uid: String, completed: FieldValue): Task<Void> =
        doc(uid).set(mapOf("completed" to completed, "updatedAt" to FieldValue.serverTimestamp()), SetOptions.merge())
}
