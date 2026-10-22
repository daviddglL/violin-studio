package com.violinstudio.domain.testing

import com.violinstudio.domain.feature.link.failure.LinkFailure
import com.violinstudio.domain.feature.link.model.TeacherCode
import com.violinstudio.domain.feature.link.model.TeacherLink
import com.violinstudio.domain.feature.link.repository.LinkRepository
import com.violinstudio.domain.feature.profile.model.TeacherRequestStatus
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** `calls` registra cada interaccion; los resultados son configurables y `links` es la fuente de ambos observadores. */
class FakeLinkRepository : LinkRepository {
    val calls = mutableListOf<String>()
    val links = MutableStateFlow<List<TeacherLink>>(emptyList())

    /** Si no es `null`, los observadores fallan con esto al recogerse. */
    var observeFailure: LinkFailure? = null
    var redeemResult: Result<String> = Result.success("teacher")
    var unlinkResult: Result<Unit> = Result.success(Unit)
    var createResult: Result<TeacherCode> = Result.success(TeacherCode("id", "ABCD2345", "45", Instant.EPOCH))
    var listResult: Result<List<TeacherCode>> = Result.success(emptyList())
    var revokeResult: Result<Unit> = Result.success(Unit)
    var teacherRequestResult: Result<TeacherRequestStatus> = Result.success(TeacherRequestStatus.PENDING)

    override fun observeAsStudent(uid: String): Flow<List<TeacherLink>> {
        calls += "observeAsStudent:$uid"
        return observe { it.studentUid == uid }
    }

    override fun observeAsTeacher(uid: String): Flow<List<TeacherLink>> {
        calls += "observeAsTeacher:$uid"
        return observe { it.teacherUid == uid }
    }

    private fun observe(filter: (TeacherLink) -> Boolean): Flow<List<TeacherLink>> = flow {
        observeFailure?.let { throw it }
        emitAll(links.map { list -> list.filter(filter) })
    }

    override suspend fun redeem(code: String, ackVersion: Int): Result<String> {
        calls += "redeem:$code:$ackVersion"
        return redeemResult
    }

    override suspend fun unlink(teacherUid: String, studentUid: String): Result<Unit> {
        calls += "unlink:$teacherUid:$studentUid"
        return unlinkResult
    }

    override suspend fun createCode(): Result<TeacherCode> {
        calls += "createCode"
        return createResult
    }

    override suspend fun listCodes(): Result<List<TeacherCode>> {
        calls += "listCodes"
        return listResult
    }

    override suspend fun revokeCode(id: String): Result<Unit> {
        calls += "revokeCode:$id"
        return revokeResult
    }

    override suspend fun requestTeacherRole(): Result<TeacherRequestStatus> {
        calls += "requestTeacherRole"
        return teacherRequestResult
    }
}
