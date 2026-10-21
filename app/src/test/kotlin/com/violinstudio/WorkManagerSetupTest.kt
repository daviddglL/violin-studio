package com.violinstudio

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import io.mockk.every
import io.mockk.mockk
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** B7b.3: WorkManager lo inicializa la app con `HiltWorkerFactory`, no el inicializador por defecto. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class WorkManagerSetupTest {
    class NoopWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result = Result.success()
    }

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `el manifest fusionado quita el inicializador por defecto de WorkManager`() {
        val provider = context.packageManager.getProviderInfo(
            ComponentName(context.packageName, "androidx.startup.InitializationProvider"),
            PackageManager.GET_META_DATA
        )
        val keys = provider.metaData?.keySet().orEmpty()
        assertFalse("androidx.work.WorkManagerInitializer sigue en el manifest: $keys", keys.any { "WorkManager" in it })
    }

    @Test
    fun `el manifest fuente lo declara con tools node remove dentro de un provider merge`() {
        val xml = File("src/main/AndroidManifest.xml").readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        assertTrue(Regex("""androidx\.work\.WorkManagerInitializer[^>]*tools:node="remove"""").containsMatchIn(xml.replace("\n", " ")))
        assertTrue("""tools:node="merge"""" in xml)
    }

    @Test
    fun `ViolinStudioApp es Configuration Provider y usa la HiltWorkerFactory inyectada`() {
        assertTrue(Configuration.Provider::class.java.isAssignableFrom(ViolinStudioApp::class.java))
        val app = ViolinStudioApp()
        val factory = mockk<HiltWorkerFactory>()
        app.workerFactory = factory
        assertSame(factory, app.workManagerConfiguration.workerFactory)
    }

    @Test
    fun `WorkManager arranca con esa configuracion y ejecuta un trabajo`() {
        val factory = mockk<HiltWorkerFactory>()
        every { factory.createWorkerWithDefaultFallback(any(), any(), any()) } answers { NoopWorker(arg(0), arg(2)) }
        val app = ViolinStudioApp().apply { workerFactory = factory }
        WorkManagerTestInitHelper.initializeTestWorkManager(context, app.workManagerConfiguration)
        val request = OneTimeWorkRequestBuilder<NoopWorker>().build()
        val manager = WorkManager.getInstance(context)
        manager.enqueue(request).result.get()
        val info = manager.getWorkInfoById(request.id).get()
        assertNotNull(info)

        assertEquals(WorkInfo.State.SUCCEEDED, info!!.state)
    }
}
