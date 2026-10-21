package com.violinstudio.data.feature.chat.datasource.firebase

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.violinstudio.data.commons.firebase.RemoteDoc
import com.violinstudio.data.commons.firebase.observeDocs
import com.violinstudio.data.feature.chat.datasource.ChatRemoteDataSource
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

private const val CHAT_MESSAGES = "chatMessages"

/**
 * Adaptador fino sobre Firestore, sin logica; se prueba en el E2E. La consulta usa el indice compuesto
 * `chatMessages(teacherUid, studentUid, createdAt)`. Las escrituras no esperan al servidor.
 */
class FirebaseChatRemoteDataSource @Inject constructor(private val db: FirebaseFirestore) : ChatRemoteDataSource {
    private val messages get() = db.collection(CHAT_MESSAGES)

    override fun observe(teacherUid: String, studentUid: String): Flow<List<RemoteDoc>> =
        messages.whereEqualTo("teacherUid", teacherUid).whereEqualTo("studentUid", studentUid)
            .orderBy("createdAt", Query.Direction.ASCENDING).observeDocs()

    override fun create(id: String, fields: Map<String, Any>) {
        messages.document(id).set(fields)
    }

    override fun delete(id: String) {
        messages.document(id).delete()
    }
}
