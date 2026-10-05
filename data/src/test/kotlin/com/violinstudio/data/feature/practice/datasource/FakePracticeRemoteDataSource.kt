package com.violinstudio.data.feature.practice.datasource

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow

class FakePracticeRemoteDataSource : PracticeRemoteDataSource {
    val snapshots = MutableSharedFlow<List<PracticeDoc>>(replay = 1)
    var observeFailure: Exception? = null
    var writeFailure: Exception? = null
    var existing = emptySet<String>()
    val observed = mutableListOf<Pair<String, Int>>()
    val creates = mutableListOf<Triple<String, String, Map<String, Any>>>()
    val updates = mutableListOf<Triple<String, String, Map<String, Any>>>()
    val deletes = mutableListOf<Pair<String, String>>()

    override fun observe(uid: String, limit: Int): Flow<List<PracticeDoc>> = flow {
        observed += uid to limit
        observeFailure?.let { throw it }
        snapshots.collect { emit(it) }
    }

    override suspend fun exists(uid: String, id: String) = id in existing

    override fun create(uid: String, id: String, fields: Map<String, Any>) {
        writeFailure?.let { throw it }
        creates += Triple(uid, id, fields)
    }

    override fun update(uid: String, id: String, fields: Map<String, Any>) {
        writeFailure?.let { throw it }
        updates += Triple(uid, id, fields)
    }

    override fun delete(uid: String, id: String) {
        writeFailure?.let { throw it }
        deletes += uid to id
    }
}
