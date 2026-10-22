package com.violinstudio

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.violinstudio.domain.feature.recording.model.DrainOutcome
import com.violinstudio.domain.feature.recording.usecase.DrainUploadQueueUseCase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Trabajo de WorkManager que vacia la cola de subida (B7b) aunque la app este en segundo plano o el sistema haya
 * matado el proceso. Lo crea la fabrica por defecto de WorkManager y saca sus dependencias de Hilt con un punto de
 * entrada, asi que no hace falta `hilt-work` ni una `Configuration` propia (y vale igual con `HiltTestApplication`).
 */
class UploadQueueWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun drainUploadQueue(): DrainUploadQueueUseCase
    }

    override suspend fun doWork(): Result {
        val drain = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java).drainUploadQueue()
        return when (drain()) {
            DrainOutcome.DONE -> Result.success()
            DrainOutcome.RETRY -> Result.retry()
        }
    }
}
