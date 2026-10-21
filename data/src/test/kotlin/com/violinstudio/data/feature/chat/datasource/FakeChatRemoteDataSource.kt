package com.violinstudio.data.feature.chat.datasource

import com.violinstudio.data.commons.firebase.RemoteDoc
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow

class FakeChatRemoteDataSource : ChatRemoteDataSource {
    val snapshots = MutableSharedFlow<List<RemoteDoc>>(replay = 1)
    var observeFailure: Exception? = null
    var writeFailure: Exception? = null
    val observed = mutableListOf<String>()
    val creates = mutableListOf<Pair<String, Map<String, Any>>>()
    val deletes = mutableListOf<String>()

    override fun observe(teacherUid: String, studentUid: String): Flow<List<RemoteDoc>> = flow {
        observed += "$teacherUid:$studentUid"
        observeFailure?.let { throw it }
        snapshots.collect { emit(it) }
    }

    override fun create(id: String, fields: Map<String, Any>) {
        writeFailure?.let { throw it }
        creates += id to fields
    }

    override fun delete(id: String) {
        writeFailure?.let { throw it }
        deletes += id
    }
}
