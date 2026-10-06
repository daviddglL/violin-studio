package com.violinstudio

import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** REQ-AUD-04/05: el micro solo se usa con la pantalla visible; sin servicios ni solicitudes al arrancar. */
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

    @Test
    fun `no declara ningun permiso de servicio en primer plano ni otros permisos de captura de audio`() {
        val requested = info.requestedPermissions.orEmpty().toList()
        assertEquals(emptyList<String>(), requested.filter { it.startsWith("android.permission.FOREGROUND_SERVICE") })
        val capture = listOf("CAPTURE_AUDIO_OUTPUT", "CAPTURE_AUDIO_HOTWORD", "CAPTURE_MEDIA_OUTPUT")
        assertEquals(emptyList<String>(), requested.filter { p -> capture.any { p.endsWith(it) } })
    }

    @Test
    fun `ningun servicio declara foregroundServiceType`() {
        val typed = info.services.orEmpty().filter { it.foregroundServiceType != 0 }.map { it.name }
        assertEquals(emptyList<String>(), typed)
    }

    @Test
    fun `RECORD_AUDIO solo se solicita desde la ruta del afinador, nunca al arrancar`() {
        val roots = listOf("../ui/src/main", "../app/src/main", "../data/src/main").map { File(it) }
        assertTrue(roots.all { it.isDirectory })
        val requesting = roots.flatMap { root -> root.walkTopDown().filter { it.extension == "kt" }.toList() }
            .filter { requestsMic(it.readText()) }
            .map { it.name }
        assertEquals(listOf("TunerRoute.kt"), requesting)
        assertTrue(requestsMic("launcher.launch(Manifest.permission.RECORD_AUDIO)"))
        assertTrue(requestsMic("requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)"))
        assertTrue(requestsMic("launcher.launch(\n    Manifest.permission.RECORD_AUDIO\n)"))
        assertFalse(requestsMic("// launcher.launch(Manifest.permission.RECORD_AUDIO)"))
    }

    private fun requestsMic(source: String): Boolean {
        val code = source.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
        return Regex("""(launch|requestPermissions?)\([^)]*?RECORD_AUDIO""", RegexOption.DOT_MATCHES_ALL)
            .containsMatchIn(code)
    }
}
