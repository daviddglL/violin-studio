package com.violinstudio

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.violinstudio.domain.feature.recording.repository.UploadScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Un unico trabajo con nombre ([WORK_NAME]) para toda la cola: solo corre con red y, si termina sin vaciarla,
 * WorkManager lo repite con espera exponencial desde [BACKOFF_SECONDS]. Sin servicio en primer plano: un video de
 * hasta 60 MB que no acabe en una ejecucion sigue en la siguiente desde la sesion reanudable de Storage.
 */
class WorkManagerUploadScheduler @Inject constructor(@ApplicationContext private val context: Context) :
    UploadScheduler {
    override fun schedule(restart: Boolean) {
        val request = OneTimeWorkRequestBuilder<UploadQueueWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        val policy = if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
    }

    companion object {
        const val WORK_NAME = "recording-upload-queue"
        const val BACKOFF_SECONDS = 30L
    }
}
