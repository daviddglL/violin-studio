package com.violinstudio.domain.testing

import com.violinstudio.domain.feature.recording.model.RecordingSummary
import com.violinstudio.domain.feature.recording.repository.RecordingConsentRepository
import com.violinstudio.domain.feature.recording.repository.RecordingLibrary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Biblioteca en memoria; [deleteResult] decide el borrado (que, si sale bien, quita la grabacion). */
class FakeRecordingLibrary(initial: List<RecordingSummary> = emptyList()) : RecordingLibrary {
    val items = MutableStateFlow(initial)
    val deleted = mutableListOf<String>()
    var deleteResult: Result<Unit> = Result.success(Unit)

    override fun observe(uid: String): Flow<List<RecordingSummary>> = items

    override suspend fun delete(uid: String, id: String): Result<Unit> = deleteResult.onSuccess {
        deleted += id
        items.value = items.value.filterNot { it.id == id }
    }
}

class FakeRecordingConsentRepository : RecordingConsentRepository {
    val calls = mutableListOf<String>()
    var grantResult: Result<Unit> = Result.success(Unit)
    var revokeResult: Result<Unit> = Result.success(Unit)
    var guardianResult: Result<String> = Result.success("t***@e***.com")

    override suspend fun grant(version: Int): Result<Unit> {
        calls += "grant:$version"
        return grantResult
    }

    override suspend fun revoke(): Result<Unit> {
        calls += "revoke"
        return revokeResult
    }

    override suspend fun requestGuardian(guardianEmail: String): Result<String> {
        calls += "guardian:$guardianEmail"
        return guardianResult
    }
}
