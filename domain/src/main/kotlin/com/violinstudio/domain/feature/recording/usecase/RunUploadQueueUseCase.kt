package com.violinstudio.domain.feature.recording.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.recording.model.QueueState
import com.violinstudio.domain.feature.recording.repository.RecordingQueue
import com.violinstudio.domain.feature.recording.repository.UploadScheduler
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Vigia de la cola de subida mientras vive el proceso: cuando el usuario con sesion tiene tomas pendientes (cualquier
 * estado salvo `failed`) pide al [UploadScheduler] que la vacie. La primera vez por usuario (arranque o cambio de
 * cuenta) reinicia el trabajo para no esperar a un reintento lejano; despues mantiene el que haya. No termina nunca: se
 * lanza una vez en el ambito del proceso. Subir lo hace [DrainUploadQueueUseCase] desde el trabajo programado.
 */
class RunUploadQueueUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val queue: RecordingQueue,
    private val scheduler: UploadScheduler
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend operator fun invoke() {
        auth.authUser.map { it?.uid }.distinctUntilChanged().collectLatest { uid ->
            if (uid == null) return@collectLatest
            var first = true
            queue.observe(uid)
                .map { items -> items.any { it.state != QueueState.FAILED } }
                .distinctUntilChanged()
                .filter { it }
                .collect {
                    scheduler.schedule(restart = first)
                    first = false
                }
        }
    }
}
