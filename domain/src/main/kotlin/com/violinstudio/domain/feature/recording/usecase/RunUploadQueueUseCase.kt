package com.violinstudio.domain.feature.recording.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.recording.model.QueueState
import com.violinstudio.domain.feature.recording.model.UploadPass
import com.violinstudio.domain.feature.recording.repository.RecordingQueue
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Motor de la cola de subida: mientras haya sesion y tomas pendientes (cualquier estado salvo `failed`) repite
 * [ProcessUploadQueueUseCase] con espera exponencial ([minDelayMs]..[maxDelayMs]) cuando nada avanza. Cambiar de
 * usuario cancela la pasada en curso. No termina nunca: se lanza una vez en el ambito del proceso.
 */
class RunUploadQueueUseCase(
    private val auth: AuthRepository,
    private val queue: RecordingQueue,
    private val process: ProcessUploadQueueUseCase,
    private val minDelayMs: Long,
    private val maxDelayMs: Long
) {
    @Inject
    constructor(
        auth: AuthRepository,
        queue: RecordingQueue,
        process: ProcessUploadQueueUseCase
    ) : this(auth, queue, process, MIN_DELAY_MS, MAX_DELAY_MS)

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend operator fun invoke() {
        auth.authUser.map { it?.uid }.distinctUntilChanged().collectLatest { uid ->
            if (uid == null) return@collectLatest
            queue.observe(uid)
                .map { items -> items.any { it.state != QueueState.FAILED } }
                .distinctUntilChanged()
                .collectLatest { pending -> if (pending) drain(uid) }
        }
    }

    private suspend fun drain(uid: String) {
        var wait = minDelayMs
        while (true) {
            val pass = try {
                process(uid)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                UploadPass.STALLED
            }
            when (pass) {
                UploadPass.IDLE -> return
                UploadPass.PROGRESSED -> wait = minDelayMs
                UploadPass.STALLED -> Unit
            }
            delay(wait)
            if (pass == UploadPass.STALLED) wait = (wait * 2).coerceAtMost(maxDelayMs)
        }
    }

    companion object {
        /** Tras subir, finalize tarda unos segundos en escribir el doc. */
        const val MIN_DELAY_MS = 5_000L
        const val MAX_DELAY_MS = 15 * 60_000L
    }
}
