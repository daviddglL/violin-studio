package com.violinstudio.domain.feature.profile.usecase

import com.violinstudio.domain.common.RetryBackoff
import com.violinstudio.domain.feature.auth.failure.AuthFailure
import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.auth.usecase.refreshClaims
import com.violinstudio.domain.feature.profile.failure.ProfileFailure
import com.violinstudio.domain.feature.profile.model.ProfileRegistration
import com.violinstudio.domain.feature.profile.repository.ProfileRepository
import javax.inject.Inject

/**
 * Registra el perfil y refresca los claims (el servidor asigna `role`). Si el refresco falla tras reintentarlo, el
 * resultado es fallo y la UI no avanza; reenviar es seguro porque `registerProfile` es idempotente.
 */
class RegisterProfileUseCase @Inject constructor(
    private val profile: ProfileRepository,
    private val auth: AuthRepository,
    private val backoff: RetryBackoff
) {
    suspend operator fun invoke(registration: ProfileRegistration): Result<Unit> {
        profile.register(registration).onFailure { return Result.failure(it) }
        return auth.refreshClaims(backoff).fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { Result.failure(it.toProfileFailure()) }
        )
    }

    private fun Throwable.toProfileFailure(): ProfileFailure =
        if (this is AuthFailure.Network) ProfileFailure.Network else ProfileFailure.Unknown(this)
}
