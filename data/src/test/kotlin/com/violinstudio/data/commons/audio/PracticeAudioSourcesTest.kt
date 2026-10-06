package com.violinstudio.data.commons.audio

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `practice-audio` (REQ-AUD-01/02/03): el audio solo vive en memoria. Test de fuentes (patron `no-collection-literals`):
 * los escaneres quitan los comentarios y se prueban a si mismos con fuentes que SI violan la regla.
 */
class PracticeAudioSourcesTest {
    private val modules = listOf("data", "domain", "ui", "app")

    private fun mainSources(): List<File> = modules.map { File("../$it/src/main") }
        .filter { it.isDirectory }
        .flatMap { root -> root.walkTopDown().filter { it.extension == "kt" }.toList() }

    private val recordSource =
        File("src/main/kotlin/com/violinstudio/data/feature/tuner/datasource/audio/AudioRecordSource.kt")

    @Test
    fun `ningun fichero que persiste o transmite referencia tipos de audio`() {
        val sources = mainSources()
        assertTrue(sources.size > 100, "escaneo vacuo: ${sources.size} fuentes")
        assertTrue(sources.any { "AudioInputSource" in it.readText() }, "el escaneo no ve el audio: seria vacuo")
        val violations = sources.flatMap { f -> persistenceAudioViolations(f.readText()).map { "${f.name}: $it" } }
        assertEquals(emptyList<String>(), violations)
    }

    @Test
    fun `AudioRecordSource no usa ficheros, MediaRecorder, logs ni telemetria`() {
        assertTrue(recordSource.isFile, "no se encontro ${recordSource.absolutePath}")
        assertEquals(emptyList<String>(), captureViolations(recordSource.readText()))
    }

    @Test
    fun `el pipeline de audio no registra nada y MediaRecorder solo elige la fuente de AudioRecord`() {
        val audioSources = mainSources().filter { f ->
            val path = f.path.replace('\\', '/')
            ("/feature/tuner/" in path || "/feature/metronome/" in path || "/commons/audio/" in path) &&
                "/src/main/" in path && ("/domain/" in path || "/data/" in path || "/ui/" in path)
        }
        assertTrue(audioSources.size > 10, "escaneo vacuo: ${audioSources.size} fuentes de audio")
        val violations = audioSources.flatMap { f -> loggingViolations(f.readText()).map { "${f.name}: $it" } }
        assertEquals(emptyList<String>(), violations)
        val recorders = mainSources().flatMap { f -> mediaRecorderViolations(f.readText()).map { "${f.name}: $it" } }
        assertEquals(emptyList<String>(), recorders)
    }

    @Test
    fun `los escaneres detectan las violaciones y ignoran los comentarios`() {
        val clean = "// import java.io.File FloatArray Log.d\n/* MediaRecorder AudioInputSource */\nclass A"
        assertEquals(emptyList<String>(), persistenceAudioViolations(clean))
        assertEquals(emptyList<String>(), captureViolations(clean))
        assertEquals(emptyList<String>(), loggingViolations(clean))
        assertEquals(emptyList<String>(), mediaRecorderViolations(clean))

        val firebase = "import com.google.firebase.firestore.Firestore\nfun f(x: FloatArray) = x"
        assertTrue(persistenceAudioViolations(firebase).isNotEmpty())
        val datastore = "import androidx.datastore.core.DataStore\nclass S(val s: AudioInputSource)"
        assertTrue(persistenceAudioViolations(datastore).isNotEmpty())
        assertTrue(persistenceAudioViolations("import java.io.File\nclass F(val f: AudioFrame)").isNotEmpty())
        assertEquals(emptyList<String>(), persistenceAudioViolations("import java.io.File\nclass F"))
        val unrelated = "import kotlin.math.abs\nfun f(a: FloatArray) = a"
        assertEquals(emptyList<String>(), persistenceAudioViolations(unrelated))

        val bad = listOf(
            "import java.io.FileOutputStream",
            "import java.io.File",
            "val f = java.io.File(\"x\")",
            "import android.media.MediaRecorder\nclass A",
            "import android.media.MediaMuxer",
            "import android.util.Log",
            "import com.google.firebase.crashlytics.FirebaseCrashlytics",
            "import com.google.firebase.analytics.FirebaseAnalytics",
            "fun f() { Log.d(\"t\", \"x\") }"
        )
        bad.forEach { assertTrue(captureViolations(it).isNotEmpty(), "no se detecta: $it") }
        assertTrue(loggingViolations("fun f() { println(freq) }").isNotEmpty())
        assertTrue(loggingViolations("import timber.log.Timber").isNotEmpty())
        assertTrue(loggingViolations("fun f() { Log.w(\"t\", \"x\") }").isNotEmpty())
        assertTrue(mediaRecorderViolations("val r = MediaRecorder()").isNotEmpty())
        assertTrue(mediaRecorderViolations("import android.media.MediaMuxer").isNotEmpty())
        // AndroidPcmRecorderFactory: MediaRecorder.AudioSource solo elige la fuente de AudioRecord.
        val factory = "import android.media.MediaRecorder\nval s = MediaRecorder.AudioSource.UNPROCESSED"
        assertEquals(emptyList<String>(), mediaRecorderViolations(factory))
    }

    private companion object {
        val blockComment = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val lineComment = Regex("""//[^\n]*""")
        val persistenceImport = Regex("""(?m)^import (com\.google\.firebase|androidx\.datastore|java\.io)\b""")
        val audioType = Regex("""\b(AudioInputSource|AudioFrame|FloatArray)\b""")
        val fileImport = Regex("""(?m)^import java\.io\.File\w*|\bjava\.io\.File\w*\(""")
        val mediaImport = Regex("""(?m)^import android\.media\.(MediaRecorder|MediaMuxer)\b""")
        val logUsage = Regex("""\b(Log|Timber)\.\w+\(|\bprintln\(|\bFirebaseCrashlytics\b|\bFirebaseAnalytics\b""")
        val telemetryImport =
            Regex("""(?m)^import (android\.util\.Log|timber\.|com\.google\.firebase\.(crashlytics|analytics))""")
        val mediaRecorderUse = Regex("""\bMediaRecorder\b(?!\.AudioSource\b)|\bMediaMuxer\b""")
        val mediaRecorderImport = Regex("""(?m)^import android\.media\.MediaRecorder\s*$""")

        fun strip(source: String) = source.replace(blockComment, "").replace(lineComment, "")

        /** Un fichero que importa Firebase/DataStore/java.io (persiste o transmite) no puede tocar tipos de audio. */
        fun persistenceAudioViolations(source: String): List<String> {
            val code = strip(source)
            if (!persistenceImport.containsMatchIn(code)) return emptyList()
            return audioType.findAll(code).map { "persiste/transmite y referencia ${it.value}" }.distinct().toList()
        }

        /** `AudioRecordSource`: ni ficheros, ni MediaRecorder/MediaMuxer, ni Log, ni Crashlytics/Analytics. */
        fun captureViolations(source: String): List<String> {
            val code = strip(source)
            return buildList {
                if (fileImport.containsMatchIn(code)) add("importa java.io.File*")
                if (mediaImport.containsMatchIn(code)) add("importa MediaRecorder/MediaMuxer")
                if (telemetryImport.containsMatchIn(code)) add("importa Log/Timber/Crashlytics/Analytics")
                if (logUsage.containsMatchIn(code)) add("registra o usa telemetria")
            }
        }

        fun loggingViolations(source: String): List<String> {
            val code = strip(source)
            return buildList {
                if (telemetryImport.containsMatchIn(code)) add("importa Log/Timber/Crashlytics/Analytics")
                if (logUsage.containsMatchIn(code)) add("registra o usa telemetria")
            }
        }

        /** Solo `MediaRecorder.AudioSource` (en `AndroidPcmRecorderFactory`, para elegir la fuente de AudioRecord). */
        fun mediaRecorderViolations(source: String): List<String> {
            val code = strip(source).replace(mediaRecorderImport, "")
            return mediaRecorderUse.findAll(code).map { "usa ${it.value}" }.distinct().toList()
        }
    }
}
