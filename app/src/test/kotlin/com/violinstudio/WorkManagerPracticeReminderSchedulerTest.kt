package com.violinstudio

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Un solo aviso programado: cambiar la hora lo sustituye y apagarlo lo anula. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WorkManagerPracticeReminderSchedulerTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scheduler = WorkManagerPracticeReminderScheduler(context)

    @Before
    fun setUp() {
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    private fun live(): List<WorkInfo> = WorkManager.getInstance(context)
        .getWorkInfosForUniqueWork(WorkManagerPracticeReminderScheduler.WORK_NAME).get()
        .filter { it.state == WorkInfo.State.ENQUEUED }

    @Test
    fun `programa un unico aviso con la espera pedida y lo sustituye al reprogramar`() {
        scheduler.schedule(Duration.ofHours(2))
        val first = live().single()
        assertTrue(first.initialDelayMillis == Duration.ofHours(2).toMillis())
        scheduler.schedule(Duration.ofHours(3))
        val second = live().single()
        assertEquals(Duration.ofHours(3).toMillis(), second.initialDelayMillis)
        assertTrue(first.id != second.id)
    }

    @Test
    fun `anular deja sin aviso`() {
        scheduler.schedule(Duration.ofHours(2))
        scheduler.cancel()
        assertTrue(live().isEmpty())
    }
}
