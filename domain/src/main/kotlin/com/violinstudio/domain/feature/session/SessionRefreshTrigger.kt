package com.violinstudio.domain.feature.session

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map

/** Origen de una peticion de refresco: las explicitas pueden degradar la sesion; las de primer plano no. */
enum class RefreshKind { EXPLICIT, FOREGROUND }

/**
 * Pide a la sesion que vuelva a consultar `identityConfig` (el servidor reevalua entonces el consentimiento del perfil).
 * Quien detecte que la politica pudo cambiar debe llamarlo: `PolicyOutdated` al aceptar, una revocacion o el regreso
 * de la app a primer plano ([requestForegroundRefresh], con limite de frecuencia). Las peticiones se funden: no hay cola.
 */
@Singleton
class SessionRefreshTrigger(private val nowMillis: () -> Long) {
    @Inject
    constructor() : this(System::currentTimeMillis)

    private val requests =
        MutableSharedFlow<RefreshKind>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private var lastForeground: Long? = null

    val kinds: Flow<RefreshKind> get() = requests

    val refreshes: Flow<Unit> get() = requests.map { }

    fun requestRefresh() {
        requests.tryEmit(RefreshKind.EXPLICIT)
    }

    /** Pensado para `ON_START` del proceso: a lo sumo una vez por [FOREGROUND_MIN_INTERVAL_MS]. */
    @Synchronized
    fun requestForegroundRefresh() {
        val now = nowMillis()
        val last = lastForeground
        if (last != null && now - last < FOREGROUND_MIN_INTERVAL_MS) return
        lastForeground = now
        requests.tryEmit(RefreshKind.FOREGROUND)
    }

    companion object {
        const val FOREGROUND_MIN_INTERVAL_MS = 30_000L
    }
}
