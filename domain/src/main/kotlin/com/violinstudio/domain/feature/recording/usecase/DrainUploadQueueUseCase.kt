package com.violinstudio.domain.feature.recording.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.recording.model.DrainOutcome
import com.violinstudio.domain.feature.recording.model.UploadPass
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.selects.select

/**
 * Una ejecucion del trabajo de subida en segundo plano: repite [ProcessUploadQueueUseCase] para el usuario con sesion
 * hasta vaciar la cola, con espera exponencial ([minDelayMs]..[maxDelayMs]) cuando nada avanza. Pasado [budgetMs] de
 * espera sin terminar devuelve [DrainOutcome.RETRY] y el programador la repite mas tarde: un trabajo de WorkManager
 * tiene unos 10 min. Si cambia el usuario corta la pasada en curso (la subida reanudable guarda su sesion).
 */
class DrainUploadQueueUseCase(
    private val auth: AuthRepository,
    private val process: ProcessUploadQueueUseCase,
    private val minDelayMs: Long,
    private val maxDelayMs: Long,
    private val budgetMs: Long
) {
    @Inject
    constructor(auth: AuthRepository, process: ProcessUploadQueueUseCase) :
        this(auth, process, MIN_DELAY_MS, MAX_DELAY_MS, BUDGET_MS)

    suspend operator fun invoke(): DrainOutcome {
        val uid = auth.authUser.first()?.uid ?: return DrainOutcome.DONE
        return coroutineScope {
            val work = async { drain(uid) }
            val switched = async { auth.authUser.map { it?.uid }.first { it != uid } }
            select {
                work.onAwait { outcome ->
                    switched.cancel()
                    outcome
                }
                switched.onAwait {
                    work.cancel()
                    DrainOutcome.DONE
                }
            }
        }
    }

    private suspend fun drain(uid: String): DrainOutcome {
        var wait = minDelayMs
        var waited = 0L
        while (true) {
            val pass = try {
                process(uid)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                UploadPass.STALLED
            }
            when (pass) {
                UploadPass.IDLE -> return DrainOutcome.DONE
                UploadPass.PROGRESSED -> wait = minDelayMs
                UploadPass.STALLED -> if (waited + wait > budgetMs) return DrainOutcome.RETRY
            }
            delay(wait)
            waited += wait
            if (pass == UploadPass.STALLED) wait = (wait * 2).coerceAtMost(maxDelayMs)
        }
    }

    companion object {
        /** Tras subir, finalize tarda unos segundos en escribir el doc. */
        const val MIN_DELAY_MS = 5_000L
        const val MAX_DELAY_MS = 60_000L

        /** Margen dentro de los 10 min que WorkManager da a un trabajo. */
        const val BUDGET_MS = 8 * 60_000L
    }
}
