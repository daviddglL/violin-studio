package com.violinstudio.data.feature.link.datasource.firebase

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.violinstudio.data.commons.firebase.FunctionsCallException
import com.violinstudio.data.feature.link.datasource.TeacherFunctionsDataSource
import javax.inject.Inject
import kotlinx.coroutines.tasks.await

/** Adaptador fino sobre los callables: solo cambia el tipo de la excepcion del SDK; se prueba en el E2E. */
class FirebaseTeacherFunctionsDataSource @Inject constructor(private val functions: FirebaseFunctions) :
    TeacherFunctionsDataSource {
    override suspend fun createCode(): Any? = call("createTeacherCode")

    override suspend fun listCodes(): Any? = call("listTeacherCodes")

    override suspend fun revokeCode(id: String): Any? = call("revokeTeacherCode", mapOf("id" to id))

    override suspend fun redeem(code: String, ackVersion: Int): Any? =
        call("redeemTeacherCode", mapOf("code" to code, "ackVersion" to ackVersion))

    override suspend fun unlink(teacherUid: String, studentUid: String): Any? =
        call("unlinkTeacher", mapOf("teacherUid" to teacherUid, "studentUid" to studentUid))

    override suspend fun requestTeacherRole(): Any? = call("requestTeacherRole")

    private suspend fun call(name: String, data: Any? = null): Any? = try {
        functions.getHttpsCallable(name).call(data).await().getData()
    } catch (e: FirebaseFunctionsException) {
        throw FunctionsCallException(e.code.name, e.details, e)
    }
}
