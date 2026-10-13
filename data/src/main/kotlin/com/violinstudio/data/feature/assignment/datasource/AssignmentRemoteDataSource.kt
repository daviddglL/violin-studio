package com.violinstudio.data.feature.assignment.datasource

import com.violinstudio.data.commons.firebase.RemoteDoc
import kotlinx.coroutines.flow.Flow

/**
 * Frontera con Firestore (`users/{studentUid}/assignments`). Las escrituras NO esperan al servidor (offline-first):
 * lanzan solo si el SDK rechaza la operacion en local; un rechazo posterior de las reglas retira el doc de la cache.
 */
interface AssignmentRemoteDataSource {
    fun observeForStudent(studentUid: String): Flow<List<RemoteDoc>>

    /** Las reglas exigen el filtro `teacherUid == me` en la consulta del profesor. */
    fun observeByTeacher(teacherUid: String, studentUid: String): Flow<List<RemoteDoc>>

    fun create(studentUid: String, id: String, fields: Map<String, Any>)

    fun update(studentUid: String, id: String, fields: Map<String, Any>)

    fun delete(studentUid: String, id: String)
}
