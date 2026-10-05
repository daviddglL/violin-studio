package com.violinstudio.data.commons.erasure

object FirestoreCachePurge {
    /**
     * Debe llamarse antes del primer uso de Firestore (`clearPersistence` falla si el cliente ya arranco). Si la purga
     * falla se conserva la marca para reintentar en el siguiente arranque y nunca se rompe el arranque.
     */
    fun runIfRequested(flag: CachePurgeFlag, clearPersistence: () -> Unit) {
        if (!flag.isRequested()) return
        try {
            clearPersistence()
            flag.clear()
        } catch (_: Exception) {
            // se reintenta en el siguiente arranque
        }
    }
}
