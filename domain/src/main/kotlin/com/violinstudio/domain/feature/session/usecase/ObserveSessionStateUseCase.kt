package com.violinstudio.domain.feature.session.usecase

import com.violinstudio.domain.common.RetryBackoff
import com.violinstudio.domain.feature.auth.model.AuthUser
import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.consent.model.IdentityConfig
import com.violinstudio.domain.feature.consent.repository.ConsentRepository
import com.violinstudio.domain.feature.profile.failure.ProfileFailure
import com.violinstudio.domain.feature.profile.repository.ProfileRepository
import com.violinstudio.domain.feature.session.SessionRefreshTrigger
import com.violinstudio.domain.feature.session.SessionState
import com.violinstudio.domain.feature.session.SessionStateResolver
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart

/**
 * Estado de sesión derivado de `authUser` + perfil + `identityConfig`. Emite `Loading` primero y NUNCA termina por un
 * fallo de repositorio:
 * - Sin sesión o con email sin verificar no consulta config ni perfil.
 * - Si `identityConfig` falla emite `Unavailable` y reintenta con espera exponencial ([RetryBackoff], cancelable)
 *   hasta que llega.
 * - Si el listener del perfil falla: `ProfileFailure.NoProfile` (permission-denied/no encontrado) se trata como
 *   perfil nulo (`NeedsProfile`); cualquier otro fallo emite `Unavailable`. En ambos casos se vuelve a escuchar con
 *   espera exponencial.
 * - Perfil `granted` en la versión vigente con claim `consentOk` falso (p. ej. confirmó el tutor): UNA llamada a
 *   `claims(forceRefresh = true)` antes de `Ready` (se rearma al salir de `Ready`); el perfil manda aunque falle.
 * - [SessionRefreshTrigger] vuelve a consultar `identityConfig` sin pasar por `Loading` (p. ej. tras aceptar una
 *   política nueva tras `PolicyOutdated`); la marca del refresco de claims se conserva.
 * - `authUser` pasa por `distinctUntilChanged`: re-emisiones iguales (p. ej. tras `reloadAndRefreshToken`) no
 *   reinician nada.
 */
class ObserveSessionStateUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val profile: ProfileRepository,
    private val consent: ConsentRepository,
    private val resolver: SessionStateResolver,
    private val backoff: RetryBackoff,
    private val trigger: SessionRefreshTrigger
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<SessionState> = auth.authUser
        .distinctUntilChanged()
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
        // La marca de refresco forzado de claims vive fuera de cada reconsulta: un refresh no la rearma.
        var refreshed = false
        emitAll(
            trigger.refreshes
                .onStart { emit(Unit) }
                .flatMapLatest {
                    flow {
                        val config = awaitConfig()
                        var attempt = 0
                        while (true) {
                            var failure: Throwable? = null
                            profile.observe(user.uid)
                                .catch { failure = it }
                                .collect { p ->
                                    attempt = 0
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
                                }
                            failure?.let {
                                refreshed = false
                                val noProfile = it is ProfileFailure.NoProfile
                                emit(if (noProfile) resolver(user, null, config) else SessionState.Unavailable)
                            }
                            delay(backoff.delayFor(attempt++))
                        }
                    }
                }
        )
    }

    private suspend fun FlowCollector<SessionState>.awaitConfig(): IdentityConfig {
        var attempt = 0
        while (true) {
            consent.identityConfig().onSuccess { return it }
            emit(SessionState.Unavailable)
            delay(backoff.delayFor(attempt++))
        }
    }

    private suspend fun AuthRepository.claimsGrantConsent(): Boolean =
        claims(forceRefresh = false).getOrNull()?.consentOk == true
}
