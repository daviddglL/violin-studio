package com.violinstudio.data.feature.link.datasource.firebase

import com.google.firebase.firestore.FirebaseFirestore
import com.violinstudio.data.commons.firebase.RemoteDoc
import com.violinstudio.data.commons.firebase.observeDocs
import com.violinstudio.data.feature.link.datasource.LinkRemoteDataSource
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

private const val LINKS = "teacherLinks"

/** Adaptador fino sobre Firestore, sin logica; se prueba en el E2E. */
class FirebaseLinkRemoteDataSource @Inject constructor(private val db: FirebaseFirestore) : LinkRemoteDataSource {
    override fun observeAsStudent(uid: String): Flow<List<RemoteDoc>> =
        db.collection(LINKS).whereEqualTo("studentUid", uid).observeDocs()

    override fun observeAsTeacher(uid: String): Flow<List<RemoteDoc>> =
        db.collection(LINKS).whereEqualTo("teacherUid", uid).observeDocs()
}
