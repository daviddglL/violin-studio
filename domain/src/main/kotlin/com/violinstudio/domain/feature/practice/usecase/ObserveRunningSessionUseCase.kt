package com.violinstudio.domain.feature.practice.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.practice.model.RunningSession
import com.violinstudio.domain.feature.practice.repository.RunningSessionStore
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** Sesión en curso del usuario con sesión (restaurada tras reiniciar el proceso); `null` si no hay. */
class ObserveRunningSessionUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val store: RunningSessionStore
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<RunningSession?> =
        auth.authUser.flatMapLatest { user -> user?.let { store.observe(it.uid) } ?: flowOf(null) }
}
