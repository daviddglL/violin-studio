package com.violinstudio.data.feature.recording.datasource.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Adaptador fino de WorkManager sobre [RecordingUploadEngine] (la logica vive alli, sin Android). Al parar WorkManager
 * el trabajo la corrutina se cancela y con ella la tarea de Storage (A14, leccion 4). Sin logs: nada sale del engine.
 */
@HiltWorker
class RecordingUploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: RecordingUploadEngine
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val uid = inputData.getString(KEY_UID)
        val id = inputData.getString(KEY_ID)
        if (uid.isNullOrEmpty() || id.isNullOrEmpty()) return Result.failure()
        return when (engine.run(uid, id)) {
            EngineResult.DONE -> Result.success()
            EngineResult.RETRY -> Result.retry()
            EngineResult.FAILED -> Result.failure()
        }
    }

    companion object {
        const val KEY_UID = "uid"
        const val KEY_ID = "id"

        fun inputData(uid: String?, id: String?): Data = Data.Builder()
            .apply {
                uid?.let { putString(KEY_UID, it) }
                id?.let { putString(KEY_ID, it) }
            }
            .build()
    }
}
