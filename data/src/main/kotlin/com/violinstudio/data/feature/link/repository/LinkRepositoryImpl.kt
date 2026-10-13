package com.violinstudio.data.feature.link.repository

import com.violinstudio.data.commons.firebase.FunctionsErrorMapper
import com.violinstudio.data.commons.firebase.RemoteDoc
import com.violinstudio.data.commons.utils.resultOf
import com.violinstudio.data.feature.link.datasource.LinkRemoteDataSource
import com.violinstudio.data.feature.link.datasource.TeacherFunctionsDataSource
import com.violinstudio.data.feature.link.utils.LinkErrorMapper
import com.violinstudio.data.feature.link.utils.TeacherCodeParser
import com.violinstudio.data.feature.link.utils.TeacherLinkParser
import com.violinstudio.data.feature.link.utils.extensions.toDomain
import com.violinstudio.domain.feature.link.model.TeacherCode
import com.violinstudio.domain.feature.link.model.TeacherLink
import com.violinstudio.domain.feature.link.repository.LinkRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class LinkRepositoryImpl @Inject constructor(
    private val remote: LinkRemoteDataSource,
    private val functions: TeacherFunctionsDataSource
) : LinkRepository {
    override fun observeAsStudent(uid: String): Flow<List<TeacherLink>> = remote.observeAsStudent(uid).decoded()

    override fun observeAsTeacher(uid: String): Flow<List<TeacherLink>> = remote.observeAsTeacher(uid).decoded()

    // Una respuesta malformada lanza MalformedResponseException, que el mapper convierte en Unknown.
    override suspend fun redeem(code: String, ackVersion: Int): Result<String> =
        call { TeacherCodeParser.parseRedeemed(functions.redeem(code, ackVersion)) }

    override suspend fun unlink(teacherUid: String, studentUid: String): Result<Unit> = call {
        functions.unlink(teacherUid, studentUid)
        Unit
    }

    override suspend fun createCode(): Result<TeacherCode> =
        call { TeacherCodeParser.parseCreated(functions.createCode()) }

    override suspend fun listCodes(): Result<List<TeacherCode>> =
        call { TeacherCodeParser.parseList(functions.listCodes()) }

    override suspend fun revokeCode(id: String): Result<Unit> = call {
        functions.revokeCode(id)
        Unit
    }

    private suspend inline fun <T> call(block: () -> T): Result<T> =
        resultOf(FunctionsErrorMapper::toLinkFailure, block)

    // Decodifica por elemento: un doc ilegible se omite sin tirar la lista.
    private fun Flow<List<RemoteDoc>>.decoded(): Flow<List<TeacherLink>> =
        map { docs -> docs.mapNotNull { TeacherLinkParser.parse(it.data).toDomain() } }
            .distinctUntilChanged()
            .catch { throw LinkErrorMapper.map(it) }
}
