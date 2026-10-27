package com.violinstudio

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.violinstudio.domain.feature.practice.repository.PracticeReminderScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Un unico trabajo con nombre ([WORK_NAME]) y espera inicial hasta la hora del aviso; sin restricciones (el aviso es
 * local). Un trabajo de un solo uso por dia, no periodico, para que siga la hora local aunque cambie la zona o la hora.
 */
class WorkManagerPracticeReminderScheduler @Inject constructor(@ApplicationContext private val context: Context) :
    PracticeReminderScheduler {
    override fun schedule(delay: Duration, afterCurrent: Boolean) {
        val request = OneTimeWorkRequestBuilder<PracticeReminderWorker>()
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .build()
        // Sustituir el trabajo que esta corriendo lo cancelaria: el propio aviso encadena el siguiente.
        val policy = if (afterCurrent) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
    }

    override fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    companion object {
        const val WORK_NAME = "practice-reminder"
    }
}
