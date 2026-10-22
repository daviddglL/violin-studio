package com.violinstudio.domain.feature.recording.repository

/**
 * Programa el vaciado de la cola de subida fuera de la pantalla visible: en la app es WorkManager, que sobrevive a
 * que el sistema mate el proceso y espera a tener red. Una sola ejecucion a la vez para toda la cola.
 */
interface UploadScheduler {
    /** [restart] cancela la ejecucion en curso o en espera y empieza ya; si no, se queda la que haya. */
    fun schedule(restart: Boolean)
}
