package com.violinstudio.domain.feature.session.usecase

import com.violinstudio.domain.common.RetryBackoff
import com.violinstudio.domain.feature.auth.model.AuthUser
import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.consent.repository.ConsentRepository
import com.violinstudio.domain.feature.profile.repository.ProfileRepository
import com.violinstudio.domain.feature.session.SessionState
import com.violinstudio.domain.feature.session.SessionStateResolver
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transform

/**
 * Estado de sesión derivado de `authUser` + perfil + `identityConfig`. Emite `Loading` primero.
 * - Sin sesión o con email sin verificar no consulta config ni perfil.
 * - Con perfil `granted` en la versión vigente pero claim `consentOk` falso (p. ej. confirmó el tutor) fuerza UNA
 *   vez `claims(forceRefresh = true)` antes de emitir `Ready`; el perfil manda aunque el refresco falle.
 * - Si `identityConfig` falla el flujo termina con ese fallo (`ConsentFailure`): el llamador debe usar `catch`.
 */
class ObserveSessionStateUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val profile: ProfileRepository,
    private val consent: ConsentRepository,
    private val resolver: SessionStateResolver,
    private val backoff: RetryBackoff
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<SessionState> = auth.authUser
        .flatMapLatest { user ->
            when {
                user == null -> flowOf(SessionState.LoggedOut)
                resolver.needsEmailVerification(user) -> flowOf(SessionState.EmailUnverified(user.email))
                else -> signedIn(user)
            }
        }
        .onStart { emit(SessionState.Loading) }
        .distinctUntilChanged()

    private fun signedIn(user: AuthUser): Flow<SessionState> = flow {
        val config = consent.identityConfig().getOrThrow()
        var refreshed = false
        profile.observe(user.uid).transform { p ->
            val state = resolver(user, p, config)
            if (state is SessionState.Ready) {
                if (!refreshed && !auth.claimsGrantConsent()) {
                    refreshed = true
                    auth.claims(forceRefresh = true)
                }
            } else {
                refreshed = false
            }
            emit(state)
        }.collect { emit(it) }
    }

    private suspend fun AuthRepository.claimsGrantConsent(): Boolean =
        claims(forceRefresh = false).getOrNull()?.consentOk == true
}
