package com.violinstudio.data.feature.assignment.datasource.firebase

import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestore
import com.violinstudio.data.commons.firebase.RemoteDoc
import com.violinstudio.data.commons.firebase.observeDocs
import com.violinstudio.data.feature.assignment.datasource.AssignmentRemoteDataSource
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

private const val USERS = "users"
private const val ASSIGNMENTS = "assignments"

/**
 * Adaptador fino sobre Firestore, sin logica; se prueba en el E2E. Las escrituras no esperan al servidor y se ignora
 * su fallo diferido: el SDK retira la escritura rechazada de la cache y los flujos se actualizan solos.
 */
class FirebaseAssignmentRemoteDataSource @Inject constructor(private val db: FirebaseFirestore) :
    AssignmentRemoteDataSource {
    private fun assignments(studentUid: String): CollectionReference =
        db.collection(USERS).document(studentUid).collection(ASSIGNMENTS)

    override fun observeForStudent(studentUid: String): Flow<List<RemoteDoc>> = assignments(studentUid).observeDocs()

    override fun observeByTeacher(teacherUid: String, studentUid: String): Flow<List<RemoteDoc>> =
        assignments(studentUid).whereEqualTo("teacherUid", teacherUid).observeDocs()

    override fun create(studentUid: String, id: String, fields: Map<String, Any>) {
        assignments(studentUid).document(id).set(fields)
    }

    override fun update(studentUid: String, id: String, fields: Map<String, Any>) {
        assignments(studentUid).document(id).update(fields)
    }

    override fun delete(studentUid: String, id: String) {
        assignments(studentUid).document(id).delete()
    }
}
