package com.violinstudio.data.feature.link.datasource

import com.violinstudio.data.commons.firebase.RemoteDoc
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow

class FakeLinkRemoteDataSource : LinkRemoteDataSource {
    val snapshots = MutableSharedFlow<List<RemoteDoc>>(replay = 1)
    var observeFailure: Exception? = null
    val observed = mutableListOf<String>()

    private fun observe(tag: String, uid: String): Flow<List<RemoteDoc>> = flow {
        observed += "$tag:$uid"
        observeFailure?.let { throw it }
        snapshots.collect { emit(it) }
    }

    override fun observeAsStudent(uid: String) = observe("student", uid)

    override fun observeAsTeacher(uid: String) = observe("teacher", uid)
}

/** `failure` hace que cualquier llamada lance; `calls` registra nombre y argumentos. */
class FakeTeacherFunctionsDataSource : TeacherFunctionsDataSource {
    var failure: Exception? = null
    var response: Any? = null
    val calls = mutableListOf<List<Any?>>()

    private fun call(vararg args: Any?): Any? {
        calls += args.toList()
        failure?.let { throw it }
        return response
    }

    override suspend fun createCode() = call("createCode")

    override suspend fun listCodes() = call("listCodes")

    override suspend fun revokeCode(id: String) = call("revokeCode", id)

    override suspend fun redeem(code: String, ackVersion: Int) = call("redeem", code, ackVersion)

    override suspend fun unlink(teacherUid: String, studentUid: String) = call("unlink", teacherUid, studentUid)

    override suspend fun requestTeacherRole() = call("requestTeacherRole")
}
