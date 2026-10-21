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
    fun `no declara el identificador de publicidad AD_ID`() {
        assertFalse(info.requestedPermissions.orEmpty().contains("com.google.android.gms.permission.AD_ID"))
    }

    @Test
    fun `Google Analytics queda desactivado en el manifiesto`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val meta = app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA).metaData
        assertTrue(meta.getBoolean("firebase_analytics_collection_deactivated"))
        assertFalse(meta.getBoolean("google_analytics_adid_collection_enabled", true))
    }

    @Test
    fun `la app no depende de firebase-analytics`() {
        assertFalse(File("build.gradle.kts").readText().contains("firebase.analytics"))
    }

    @Test
    fun `ningun servicio declara foregroundServiceType`() {
        val typed = info.services.orEmpty().filter { it.foregroundServiceType != 0 }.map { it.name }
        assertEquals(emptyList<String>(), typed)
    }

    @Test
    fun `4b no declara permisos ni servicios de camara y las copias de seguridad siguen apagadas`() {
        val requested = info.requestedPermissions.orEmpty().toList()
        assertFalse(requested.contains("android.permission.FOREGROUND_SERVICE_CAMERA"))
        assertFalse(requested.contains(Manifest.permission.CAMERA))
        val camera = info.services.orEmpty().filter {
            it.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA != 0
        }
        assertTrue(camera.isEmpty())
    }

    @Test
    fun `el manifest fuente no declara servicios de captura ni foregroundServiceType`() {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue(manifest.isFile)
        val xml = manifest.readText()
        assertTrue("android:allowBackup=\"false\"" in xml)
        assertEquals(emptyList<String>(), captureManifestViolations(xml))
        val cameraPermission = "<uses-permission android:name=\"android.permission.FOREGROUND_SERVICE_CAMERA\"/>"
        assertTrue(captureManifestViolations(cameraPermission).isNotEmpty())
        assertTrue(captureManifestViolations("<service android:foregroundServiceType=\"microphone\" />").isNotEmpty())
        assertTrue(captureManifestViolations("<!-- foregroundServiceType --><application/>").isEmpty())
    }

    private fun captureManifestViolations(xml: String): List<String> {
        val code = xml.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        return buildList {
            if (Regex("""android\.permission\.FOREGROUND_SERVICE""").containsMatchIn(code)) add("permiso de servicio")
            if ("foregroundServiceType" in code) add("foregroundServiceType")
        }
    }

    @Test
    fun `RECORD_AUDIO solo se solicita desde las rutas del afinador y de grabaciones, nunca al arrancar`() {
        val roots = listOf("../ui/src/main", "../app/src/main", "../data/src/main").map { File(it) }
        assertTrue(roots.all { it.isDirectory })
        val requesting = roots.flatMap { root -> root.walkTopDown().filter { it.extension == "kt" }.toList() }
            .filter { requestsMic(it.readText()) }
            .map { it.name }
        assertEquals(setOf("TunerRoute.kt", "RecordingsRoute.kt"), requesting.toSet())
        assertEquals(2, requesting.size)
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
