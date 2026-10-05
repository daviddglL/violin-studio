package com.violinstudio

import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ManifestPermissionsTest {
    private val info = ApplicationProvider.getApplicationContext<android.app.Application>().let {
        it.packageManager.getPackageInfo(
            it.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES
        )
    }

    @Test
    fun `declara RECORD_AUDIO`() {
        assertTrue(info.requestedPermissions.orEmpty().contains(Manifest.permission.RECORD_AUDIO))
    }

    @Test
    fun `no declara FOREGROUND_SERVICE_MICROPHONE ni servicios de microfono`() {
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.FOREGROUND_SERVICE_MICROPHONE"))
        val micServices = info.services.orEmpty().filter {
            it.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0
        }
        assertTrue(micServices.isEmpty())
    }
}
