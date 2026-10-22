package com.violinstudio

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Un solo trabajo con red para toda la cola: `restart` lo sustituye y si no se queda el que haya. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WorkManagerUploadSchedulerTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scheduler = WorkManagerUploadScheduler(context)

    @Before
    fun setUp() {
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    private fun works(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(WorkManagerUploadScheduler.WORK_NAME).get()

    @Test
    fun `programa un trabajo que espera a tener red`() {
        scheduler.schedule(restart = false)
        val work = works().single()
        assertEquals(WorkInfo.State.ENQUEUED, work.state)
        assertEquals(NetworkType.CONNECTED, work.constraints.requiredNetworkType)
    }

    @Test
    fun `sin restart se queda el trabajo que ya habia`() {
        scheduler.schedule(restart = false)
        val first = works().single().id
        scheduler.schedule(restart = false)
        assertEquals(listOf(first), works().map { it.id })
    }

    @Test
    fun `con restart sustituye el trabajo en espera`() {
        scheduler.schedule(restart = false)
        val first = works().single().id
        scheduler.schedule(restart = true)
        val live = works().filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(1, live.size)
        assertEquals(false, live.single().id == first)
    }
}
