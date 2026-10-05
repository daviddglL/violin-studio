package com.violinstudio.domain.feature.tuner.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.tuner.model.TunerConfig
import com.violinstudio.domain.feature.tuner.repository.TunerConfigRepository
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** Config del usuario con sesión; sin sesión, los valores por defecto. */
class ObserveTunerConfigUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val repo: TunerConfigRepository
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<TunerConfig> =
        auth.authUser.flatMapLatest { user -> user?.let { repo.observe(it.uid) } ?: flowOf(TunerConfig()) }
}
