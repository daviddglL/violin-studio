package com.violinstudio.data.feature.recording.datasource.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.violinstudio.domain.feature.recording.repository.RecordingUploadScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Trabajo unico `rec-upload-{id}` con KEEP (A14), restriccion CONNECTED y backoff exponencial desde 30 s. El tope de
 * 10 intentos lo aplica el engine con los intentos persistidos en la cola.
 */
@Singleton
class WorkManagerRecordingUploadScheduler @Inject constructor(@param:ApplicationContext private val context: Context) :
    RecordingUploadScheduler {
    override fun schedule(uid: String, id: String) {
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(id), POLICY, request(uid, id))
    }

    override fun cancel(id: String) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName(id))
    }

    companion object {
        val POLICY = ExistingWorkPolicy.KEEP
        const val BACKOFF_SECONDS = 30L

        fun uniqueName(id: String) = "rec-upload-$id"

        fun request(uid: String, id: String): OneTimeWorkRequest =
            OneTimeWorkRequest.Builder(RecordingUploadWorker::class.java)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .setInputData(RecordingUploadWorker.inputData(uid, id))
                .build()
    }
}
