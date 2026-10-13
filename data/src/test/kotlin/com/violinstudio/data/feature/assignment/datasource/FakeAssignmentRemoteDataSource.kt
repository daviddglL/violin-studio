package com.violinstudio.data.feature.assignment.datasource

import com.violinstudio.data.commons.firebase.RemoteDoc
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow

class FakeAssignmentRemoteDataSource : AssignmentRemoteDataSource {
    val snapshots = MutableSharedFlow<List<RemoteDoc>>(replay = 1)
    var observeFailure: Exception? = null
    var writeFailure: Exception? = null
    val observed = mutableListOf<String>()
    val creates = mutableListOf<Triple<String, String, Map<String, Any>>>()
    val updates = mutableListOf<Triple<String, String, Map<String, Any>>>()
    val deletes = mutableListOf<Pair<String, String>>()

    private fun observe(tag: String): Flow<List<RemoteDoc>> = flow {
        observed += tag
        observeFailure?.let { throw it }
        snapshots.collect { emit(it) }
    }

    override fun observeForStudent(studentUid: String) = observe("student:$studentUid")

    override fun observeByTeacher(teacherUid: String, studentUid: String) = observe("teacher:$teacherUid:$studentUid")

    override fun create(studentUid: String, id: String, fields: Map<String, Any>) {
        writeFailure?.let { throw it }
        creates += Triple(studentUid, id, fields)
    }

    override fun update(studentUid: String, id: String, fields: Map<String, Any>) {
        writeFailure?.let { throw it }
        updates += Triple(studentUid, id, fields)
    }

    override fun delete(studentUid: String, id: String) {
        writeFailure?.let { throw it }
        deletes += studentUid to id
    }
}
