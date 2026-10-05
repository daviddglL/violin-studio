package com.violinstudio.data.feature.practice.repository

import com.violinstudio.data.feature.practice.datasource.PracticeRemoteDataSource
import com.violinstudio.data.feature.practice.utils.PracticeErrorMapper
import com.violinstudio.data.feature.practice.utils.PracticeSessionParser
import com.violinstudio.data.feature.practice.utils.extensions.notesUpdate
import com.violinstudio.data.feature.practice.utils.extensions.toDomain
import com.violinstudio.data.feature.practice.utils.extensions.toFields
import com.violinstudio.domain.feature.practice.model.PracticeDraft
import com.violinstudio.domain.feature.practice.model.PracticeSession
import com.violinstudio.domain.feature.practice.repository.PracticeLogRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

class FirestorePracticeLogRepository @Inject constructor(private val remote: PracticeRemoteDataSource) :
    PracticeLogRepository {
    override fun observeHistory(uid: String, limit: Int): Flow<List<PracticeSession>> = remote.observe(uid, limit)
        .map { docs -> docs.mapNotNull { PracticeSessionParser.parse(it.id, it.data, it.hasPendingWrites).toDomain() } }
        .catch { throw PracticeErrorMapper.map(it) }

    override suspend fun create(uid: String, draft: PracticeDraft): Result<Unit> =
        write { remote.create(uid, draft.id, draft.toFields()) }

    override suspend fun exists(uid: String, id: String): Boolean = remote.exists(uid, id)

    override suspend fun updateNotes(uid: String, id: String, notes: String?): Result<Unit> =
        write { remote.update(uid, id, notesUpdate(notes)) }

    override suspend fun delete(uid: String, id: String): Result<Unit> = write { remote.delete(uid, id) }

    private inline fun write(block: () -> Unit): Result<Unit> = try {
        block()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(PracticeErrorMapper.map(e))
    }
}
