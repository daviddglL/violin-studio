package com.violinstudio.domain.feature.session.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.consent.repository.ConsentRepository
import com.violinstudio.domain.feature.profile.repository.ProfileRepository
import com.violinstudio.domain.feature.session.SessionState
import com.violinstudio.domain.feature.session.SessionStateResolver
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

class ObserveSessionStateUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val profile: ProfileRepository,
    private val consent: ConsentRepository,
    private val resolver: SessionStateResolver
) {
    operator fun invoke(): Flow<SessionState> = throw NotImplementedError()
}
