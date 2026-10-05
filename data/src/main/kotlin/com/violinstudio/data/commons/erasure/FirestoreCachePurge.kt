package com.violinstudio.data.commons.erasure

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

object FirestoreCachePurge {
    const val DEFAULT_TIMEOUT_MS = 3_000L

    /**
     * Debe llamarse antes del primer uso de Firestore (`clearPersistence` falla si el cliente ya arranco). Bloquea como
     * maximo [timeoutMs]; si la purga falla o vence se conserva la marca para reintentar en el siguiente arranque y
     * nunca se rompe el arranque.
     */
    fun runIfRequested(
        flag: CachePurgeFlag,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        clearPersistence: suspend () -> Unit
    ) {
        if (!flag.isRequested()) return
        try {
            val done = runBlocking(Dispatchers.IO) { withTimeoutOrNull(timeoutMs) { clearPersistence() } }
            if (done != null) flag.clear()
        } catch (_: Exception) {
            // se reintenta en el siguiente arranque
        }
    }
}
