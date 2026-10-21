package com.violinstudio

import com.violinstudio.domain.feature.recording.usecase.RunUploadQueueUseCase
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Lanza el motor de la cola de subida de grabaciones (B7b) una sola vez, en un ambito que vive lo que el proceso. Con
 * el proceso muerto la cola sigue en DataStore y se reanuda en el siguiente arranque.
 */
@Singleton
class UploadQueueStarter @Inject constructor(private val run: RunUploadQueueUseCase) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun install() {
        if (job == null) job = scope.launch { run() }
    }
}
