package com.violinstudio.domain.feature.practice.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.practice.model.PracticeRules
import com.violinstudio.domain.feature.practice.model.PracticeSession
import com.violinstudio.domain.feature.practice.repository.PracticeLogRepository
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Historial del usuario con sesión, `startedAt` desc, como mucho 200; vacío sin sesión. */
class ObservePracticeHistoryUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val repo: PracticeLogRepository
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<List<PracticeSession>> = auth.authUser.flatMapLatest { user ->
        if (user == null) {
            flowOf(emptyList())
        } else {
            repo.observeHistory(user.uid, PracticeRules.HISTORY_LIMIT).map { list ->
                list.sortedByDescending { it.startedAt }.take(PracticeRules.HISTORY_LIMIT)
            }
        }
    }
}
