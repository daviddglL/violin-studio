package com.violinstudio.data.feature.assignment.repository

import com.violinstudio.data.commons.firebase.RemoteDoc
import com.violinstudio.data.feature.assignment.datasource.AssignmentRemoteDataSource
import com.violinstudio.data.feature.assignment.utils.AssignmentErrorMapper
import com.violinstudio.data.feature.assignment.utils.AssignmentParser
import com.violinstudio.data.feature.assignment.utils.extensions.doneFields
import com.violinstudio.data.feature.assignment.utils.extensions.toCreateFields
import com.violinstudio.data.feature.assignment.utils.extensions.toDomain
import com.violinstudio.data.feature.assignment.utils.extensions.toUpdateFields
import com.violinstudio.domain.feature.assignment.model.Assignment
import com.violinstudio.domain.feature.assignment.model.AssignmentDraft
import com.violinstudio.domain.feature.assignment.repository.AssignmentRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class AssignmentRepositoryImpl @Inject constructor(private val remote: AssignmentRemoteDataSource) :
    AssignmentRepository {
    override fun observeForStudent(studentUid: String): Flow<List<Assignment>> =
        remote.observeForStudent(studentUid).decoded()

    override fun observeByTeacher(teacherUid: String, studentUid: String): Flow<List<Assignment>> =
        remote.observeByTeacher(teacherUid, studentUid).decoded()

    override suspend fun create(studentUid: String, teacherUid: String, draft: AssignmentDraft): Result<Unit> =
        write { remote.create(studentUid, draft.id, draft.toCreateFields(teacherUid)) }

    override suspend fun update(studentUid: String, draft: AssignmentDraft): Result<Unit> =
        write { remote.update(studentUid, draft.id, draft.toUpdateFields()) }

    override suspend fun setDone(studentUid: String, id: String, done: Boolean): Result<Unit> =
        write { remote.update(studentUid, id, doneFields(done)) }

    override suspend fun delete(studentUid: String, id: String): Result<Unit> = write { remote.delete(studentUid, id) }

    // Decodifica por elemento: un doc ilegible se omite sin tirar la lista.
    private fun Flow<List<RemoteDoc>>.decoded(): Flow<List<Assignment>> =
        map { docs -> docs.mapNotNull { AssignmentParser.parse(it.id, it.data, it.hasPendingWrites).toDomain() } }
            .distinctUntilChanged()
            .catch { throw AssignmentErrorMapper.map(it) }

    private inline fun write(block: () -> Unit): Result<Unit> = try {
        block()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(AssignmentErrorMapper.map(e))
    }
}
