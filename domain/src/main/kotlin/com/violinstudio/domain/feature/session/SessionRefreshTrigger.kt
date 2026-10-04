package com.violinstudio.domain.feature.session

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Pide a la sesión que vuelva a consultar `identityConfig` (el servidor reevalúa entonces el consentimiento del perfil).
 * Quien detecte que la política pudo cambiar debe llamarlo: `PolicyOutdated` al aceptar, una revocación (8a) o el
 * regreso de la app a primer plano. Las peticiones se funden: no hay cola.
 */
@Singleton
class SessionRefreshTrigger @Inject constructor() {
    private val requests =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val refreshes: Flow<Unit> get() = requests

    fun requestRefresh() {
        requests.tryEmit(Unit)
    }
}
