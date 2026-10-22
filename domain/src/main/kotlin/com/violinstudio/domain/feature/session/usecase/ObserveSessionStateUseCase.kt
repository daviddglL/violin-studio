package com.violinstudio.domain.feature.session.usecase

import com.violinstudio.domain.common.RetryBackoff
import com.violinstudio.domain.feature.auth.model.AuthUser
import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.consent.PendingGuardianEmail
import com.violinstudio.domain.feature.consent.model.IdentityConfig
import com.violinstudio.domain.feature.consent.repository.ConsentRepository
import com.violinstudio.domain.feature.consent.repository.IdentityConfigCache
import com.violinstudio.domain.feature.profile.failure.ProfileFailure
import com.violinstudio.domain.feature.profile.repository.ProfileRepository
import com.violinstudio.domain.feature.session.RefreshKind
import com.violinstudio.domain.feature.session.SessionRefreshTrigger
import com.violinstudio.domain.feature.session.SessionState
import com.violinstudio.domain.feature.session.SessionStateResolver
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * Estado de sesión derivado de `authUser` + perfil + `identityConfig`. Emite `Loading` primero y NUNCA termina por un
 * fallo de repositorio:
 * - Sin sesión o con email sin verificar no consulta config ni perfil.
 * - Si `identityConfig` falla emite `Unavailable` y reintenta con espera exponencial ([RetryBackoff], cancelable)
 *   hasta que llega. Excepcion: al arrancar la sesion, si hay una config guardada ([IdentityConfigCache]) se usa sin
 *   emitir `Unavailable` (la app se puede usar sin conexion) y se sigue pidiendo la del servidor en segundo plano;
 *   cuando llega se reevalua como un refresco explicito. Cada config que llega del servidor se guarda.
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
    private val trigger: SessionRefreshTrigger,
    private val pendingGuardianEmail: PendingGuardianEmail,
    private val cache: IdentityConfigCache
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<SessionState> = auth.authUser
        .distinctUntilChanged()
        .flatMapLatest { user ->
            // Un usuario distinto (o ninguno) olvida el email de tutor del anterior, pase o no por LoggedOut.
            pendingGuardianEmail.retainOnlyFor(user?.uid)
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
        // Ultimo estado y config conocidos: un refresco de primer plano que falla no debe degradar un Ready.
        var lastState: SessionState? = null
        var lastConfig: IdentityConfig? = null
        // La config en uso salio de la cache (sin conexion): hay que seguir pidiendo la del servidor.
        var lastFromCache = false
        // Config del servidor que trajo el reintento en segundo plano: la usa la reconsulta que dispara.
        var prefetched: IdentityConfig? = null
        val fresh =
            MutableSharedFlow<IdentityConfig>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        emitAll(
            merge(
                trigger.kinds,
                fresh.map {
                    prefetched = it
                    RefreshKind.EXPLICIT
                }
            )
                .onStart { emit(RefreshKind.EXPLICIT) }
                .flatMapLatest { kind ->
                    flow {
                        val known = lastConfig
                        val keepReady = kind == RefreshKind.FOREGROUND && known != null &&
                            lastState is SessionState.Ready
                        val taken = prefetched.also { prefetched = null }
                        val (config, fromCache) = when {
                            taken != null -> taken to false
                            keepReady -> fetch(user.uid)?.let { it to false } ?: (known!! to lastFromCache)
                            else -> awaitConfig(user.uid, allowCache = known == null) {
                                lastState = SessionState.Unavailable
                            }
                        }
                        lastConfig = config
                        lastFromCache = fromCache
                        coroutineScope {
                            // Solo lanza: el hijo nunca emite, asi que el flujo no emite desde otra corrutina.
                            if (fromCache) launch { fresh.tryEmit(retryFresh(user.uid)) }
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
                                        lastState = state
                                        emit(state)
                                    }
                                failure?.let {
                                    refreshed = false
                                    val noProfile = it is ProfileFailure.NoProfile
                                    val failed =
                                        if (noProfile) resolver(user, null, config) else SessionState.Unavailable
                                    lastState = failed
                                    emit(failed)
                                }
                                delay(backoff.delayFor(attempt++))
                            }
                        }
                    }
                }
        )
    }

    /** Config del servidor (y la guarda), o `null` si la llamada falla. */
    private suspend fun fetch(uid: String): IdentityConfig? =
        consent.identityConfig().getOrNull()?.also { cache.remember(uid, it) }

    /** Devuelve la config y si salio de la cache. Con [allowCache] y config guardada, el primer fallo no espera. */
    private suspend fun FlowCollector<SessionState>.awaitConfig(
        uid: String,
        allowCache: Boolean,
        onUnavailable: () -> Unit
    ): Pair<IdentityConfig, Boolean> {
        var attempt = 0
        while (true) {
            fetch(uid)?.let { return it to false }
            if (attempt == 0 && allowCache) cache.lastKnown(uid)?.let { return it to true }
            onUnavailable()
            emit(SessionState.Unavailable)
            delay(backoff.delayFor(attempt++))
        }
    }

    private suspend fun retryFresh(uid: String): IdentityConfig {
        var attempt = 0
        while (true) {
            delay(backoff.delayFor(attempt++))
            fetch(uid)?.let { return it }
        }
    }

    private suspend fun AuthRepository.claimsGrantConsent(): Boolean =
        claims(forceRefresh = false).getOrNull()?.consentOk == true
}
