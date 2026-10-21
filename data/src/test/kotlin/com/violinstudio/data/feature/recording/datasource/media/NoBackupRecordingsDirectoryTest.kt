package com.violinstudio.data.feature.recording.datasource.media

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** REQ-REC-03 / REQ-AUD-05: las tomas viven en `noBackupFilesDir/recordings/{uid}` y en ningun otro sitio. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoBackupRecordingsDirectoryTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `el directorio del uid cuelga de noBackupFilesDir`() {
        val dir = NoBackupRecordingsDirectory(context).forUid("uid1")
        assertEquals(File(File(context.noBackupFilesDir, "recordings"), "uid1"), dir)
        assertTrue(dir.path.startsWith(context.noBackupFilesDir.path))
    }

    @Test
    fun `no usa filesDir, cacheDir ni almacenamiento externo`() {
        val dir = NoBackupRecordingsDirectory(context).forUid("uid1")
        assertFalse(dir.path.startsWith(context.cacheDir.path))
        assertFalse(dir.path.startsWith(context.filesDir.path + File.separator + "recordings"))
        assertFalse(dir.path.startsWith(context.getExternalFilesDir(null)?.path ?: "\u0000"))
    }

    @Test
    fun `las fuentes de grabacion solo usan noBackupFilesDir`() {
        val sources = File("src/main/kotlin/com/violinstudio/data/feature/recording").walkTopDown()
            .filter { it.extension == "kt" }.toList()
        assertTrue("escaneo vacuo: ${sources.size}", sources.size >= 4)
        assertTrue("seria vacuo", sources.any { "noBackupFilesDir" in it.readText() })
        val forbidden = Regex("""\b(filesDir|cacheDir|externalCacheDir|getExternalFilesDir|externalMediaDirs)\b""")
        val offenders = sources.filter { forbidden.containsMatchIn(stripComments(it.readText())) }.map { it.name }
        assertEquals(emptyList<String>(), offenders)
        assertTrue(forbidden.containsMatchIn("val d = context.cacheDir"))
    }

    private fun stripComments(source: String) = source
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")
}
