package com.violinstudio

import com.violinstudio.domain.feature.practice.usecase.SyncPracticeReminderUseCase
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Lanza una sola vez, para lo que viva el proceso, la sincronizacion del recordatorio con la cuenta y su ajuste. */
@Singleton
class PracticeReminderStarter @Inject constructor(private val sync: SyncPracticeReminderUseCase) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun install() {
        if (job == null) job = scope.launch { sync() }
    }
}
