package com.violinstudio.data.feature.recording.datasource.media.video

import com.violinstudio.data.feature.recording.datasource.media.RecordingsDirectory
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Video (4c): rutas de la toma y borrado limitado a los ficheros de video del uid. */
class NoBackupVideoFilesTest {
    @TempDir
    lateinit var root: File

    private val directories = RecordingsDirectory { uid -> File(root, "recordings/$uid") }
    private val files by lazy { NoBackupVideoFiles(directories, Dispatchers.Unconfined) }
    private val dir by lazy { File(root, "recordings/uid1").apply { mkdirs() } }

    private fun touch(name: String, parent: File = dir) = File(parent, name).apply {
        parentFile.mkdirs()
        writeText("x")
    }

    @Test
    fun `el original y el difuminado viven en el directorio sin copia del uid`() {
        assertEquals(File(dir, "v-1.raw.mp4").path, files.rawPath("uid1", "v-1"))
        assertEquals(File(dir, "v-1.mp4").path, files.outputPath("uid1", "v-1"))
    }

    @Test
    fun `delete borra el fichero de video y su parcial`() = runTest {
        val out = touch("v-1.mp4")
        val part = touch("v-1.mp4.part")
        val raw = touch("v-1.raw.mp4")
        files.delete("uid1", out.path)
        files.delete("uid1", raw.path)
        assertFalse(out.exists() || part.exists() || raw.exists())
        files.delete("uid1", out.path)
    }

    @Test
    fun `delete no toca audio, rutas de otro uid ni nombres invalidos`() = runTest {
        val audio = touch("a-1.m4a")
        val other = touch("v-1.mp4", File(root, "recordings/uid2"))
        val odd = touch("a_b.mp4")
        files.delete("uid1", audio.path)
        files.delete("uid1", other.path)
        files.delete("uid1", odd.path)
        files.delete("uid1", File(dir, "../uid2/v-1.mp4").path)
        assertTrue(audio.isFile && other.isFile && odd.isFile)
    }

    @Test
    fun `sweepRaw crea el directorio y borra solo los originales huerfanos`() = runTest {
        File(root, "recordings/fresh").let {
            files.sweepRaw("fresh")
            assertTrue(it.isDirectory)
        }
        val raw = touch("old.raw.mp4")
        val blurred = touch("old.mp4")
        val audio = touch("a.m4a")
        files.sweepRaw("uid1")
        assertFalse(raw.exists())
        assertTrue(blurred.isFile && audio.isFile)
    }
}
