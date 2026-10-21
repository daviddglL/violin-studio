package com.violinstudio.domain.feature.recording.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.recording.model.RecordingsOverview
import com.violinstudio.domain.feature.recording.repository.RecordingLibrary
import com.violinstudio.domain.feature.recording.repository.RecordingQueue
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/** Cola local y grabaciones del servidor del usuario actual. Sin sesion no emite. El flujo falla si Firestore falla. */
class ObserveRecordingsUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val queue: RecordingQueue,
    private val library: RecordingLibrary
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<RecordingsOverview> = auth.authUser.map { it?.uid }.distinctUntilChanged()
        .flatMapLatest { uid ->
            if (uid == null) {
                emptyFlow()
            } else {
                combine(queue.observe(uid), library.observe(uid)) { pending, recordings ->
                    val onServer = recordings.map { it.id }.toSet()
                    RecordingsOverview(uid, pending.filterNot { it.id in onServer }, recordings)
                }
            }
        }
}

class DeleteRecordingUseCase @Inject constructor(private val library: RecordingLibrary) {
    suspend operator fun invoke(uid: String, id: String): Result<Unit> = library.delete(uid, id)
}
