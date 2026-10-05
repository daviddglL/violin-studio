package com.violinstudio.data.commons

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `data` se prueba con `unitTests.isReturnDefaultValues = true` (el SDK de Firebase llama a `android.*` al
 * construir sus excepciones). Eso haría que una llamada Android en código de producción "pasara" en los tests
 * sin ejecutar nada real, así que el código principal no puede importar `android.*`.
 */
class NoAndroidInMainTest {
    /** Imports permitidos explícitamente (ruta relativa a `src/main/kotlin` -> import): solo los adaptadores de audio. */
    private val allowList = setOf(
        "com/violinstudio/data/commons/di/DataStoreModule.kt" to "android.content.Context",
        "com/violinstudio/data/commons/di/AudioModule.kt" to "android.content.Context",
        "com/violinstudio/data/commons/erasure/SharedPrefsCachePurgeFlag.kt" to "android.content.Context",
        "com/violinstudio/data/commons/di/AudioModule.kt" to "android.media.AudioManager",
        "com/violinstudio/data/feature/tuner/datasource/audio/ContextMicPermission.kt" to "android.Manifest",
        "com/violinstudio/data/feature/tuner/datasource/audio/ContextMicPermission.kt" to "android.content.Context",
        "com/violinstudio/data/feature/tuner/datasource/audio/ContextMicPermission.kt" to
            "android.content.pm.PackageManager"
    ) + listOf(
        "android.media.AudioFormat",
        "android.media.AudioManager",
        "android.media.AudioRecord",
        "android.media.MediaRecorder"
    ).map { "com/violinstudio/data/feature/tuner/datasource/audio/AndroidPcmRecorderFactory.kt" to it } + listOf(
        "android.media.AudioAttributes",
        "android.media.AudioFormat",
        "android.media.AudioTrack"
    ).map { "com/violinstudio/data/commons/audio/AndroidPcmTrackFactory.kt" to it }

    @Test
    fun `el codigo principal de data no importa android`() {
        val root = File("src/main/kotlin")
        assertTrue(root.isDirectory, "no se encontró ${root.absolutePath}")
        val offenders = root.walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { file ->
                file.readLines().filter { it.trim().startsWith("import android.") }
                    .map { file.relativeTo(root).path.replace('\\', '/') to it.trim().removePrefix("import ") }
            }
            .filterNot { it in allowList }
            .toList()
        assertEquals(emptyList<Pair<String, String>>(), offenders)
    }
}
