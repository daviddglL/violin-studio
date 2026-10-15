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

    private fun unix(f: File) = f.path.replace('\\', '/')

    private fun isMediaDatasource(f: File): Boolean {
        val path = unix(f)
        return "/data/src/main/" in path && "/feature/recording/datasource/media/" in path
    }

    private fun recordingFeature(f: File): Boolean {
        val path = unix(f)
        return "/src/main/" in path && ("/feature/recording/" in path || "/feature/video/" in path)
    }

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
        val recorders = mainSources().filterNot(::isMediaDatasource)
            .flatMap { f -> mediaRecorderViolations(f.readText()).map { "${f.name}: $it" } }
        assertEquals(emptyList<String>(), recorders)
    }

    /** REQ-AUD-01 (delta B6): `MediaRecorder`/`MediaMuxer`/`MediaCodec` solo bajo `data/feature/recording/datasource/media/`. */
    @Test
    fun `MediaRecorder vive solo en el datasource media de grabacion`() {
        val inMedia = mainSources().filter(::isMediaDatasource)
        assertTrue(inMedia.size >= 2, "escaneo vacuo: ${inMedia.size} fuentes en datasource/media")
        assertTrue(
            inMedia.any { "import android.media.MediaRecorder" in it.readText() },
            "el escaneo no ve el MediaRecorder real: seria vacuo"
        )
        val outside = mainSources().filterNot(::isMediaDatasource)
            .flatMap { f -> mediaRecorderViolations(f.readText()).map { "${f.name}: $it" } }
        assertEquals(emptyList<String>(), outside)
        val storage = mainSources().filterNot { "/feature/recording/" in unix(it) && "/data/" in unix(it) }
            .flatMap { f -> storageViolations(f.readText()).map { "${f.name}: $it" } }
        assertEquals(emptyList<String>(), storage)
    }

    /** REQ-AUD-07/REQ-REC-12: la grabacion no toca la tuberia del afinador ni del metronomo, y viceversa. */
    @Test
    fun `grabacion y video no referencian el afinador ni el metronomo, y estos no referencian la grabacion`() {
        val recording = mainSources().filter { recordingFeature(it) }
        assertTrue(recording.size >= 8, "escaneo vacuo: ${recording.size} fuentes de grabacion")
        val tunerRefs = recording.flatMap { f -> tunerReferenceViolations(f.readText()).map { "${f.name}: $it" } }
        assertEquals(emptyList<String>(), tunerRefs)
        val pipeline = mainSources().filter { f ->
            "/feature/tuner/" in unix(f) || "/feature/metronome/" in unix(f) || "/commons/audio/" in unix(f)
        }
        assertTrue(pipeline.size > 10, "escaneo vacuo: ${pipeline.size} fuentes de audio")
        val reverse = pipeline.flatMap { f -> recordingReferenceViolations(f.readText()).map { "${f.name}: $it" } }
        assertEquals(emptyList<String>(), reverse)
    }

    /** REQ-AUD-03/REQ-REC-12: sin `Log`/Timber/Crashlytics/Analytics en las features de grabacion y video. */
    @Test
    fun `las features de grabacion y video no registran ni usan telemetria`() {
        val recording = mainSources().filter { recordingFeature(it) }
        assertTrue(recording.size >= 8, "escaneo vacuo: ${recording.size} fuentes de grabacion")
        val violations = recording.flatMap { f -> loggingViolations(f.readText()).map { "${f.name}: $it" } }
        assertEquals(emptyList<String>(), violations)
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
        assertTrue(mediaRecorderViolations("val c = MediaCodec.createEncoderByType(t)").isNotEmpty())
        assertTrue(storageViolations("import com.google.firebase.storage.StorageReference").isNotEmpty())
        assertEquals(emptyList<String>(), storageViolations("// StorageReference\nclass A"))
        val tunerImport = "import com.violinstudio.domain.feature.tuner.audio.AudioInputSource"
        assertTrue(tunerReferenceViolations(tunerImport).isNotEmpty())
        assertTrue(tunerReferenceViolations("val f: AudioFrame").isNotEmpty())
        assertTrue(tunerReferenceViolations("import com.violinstudio.domain.feature.metronome.X").isNotEmpty())
        assertEquals(emptyList<String>(), tunerReferenceViolations("// AudioInputSource\nclass Recorder"))
        assertTrue(recordingReferenceViolations("import com.violinstudio.data.feature.recording.X").isNotEmpty())
        assertEquals(emptyList<String>(), recordingReferenceViolations("// feature.recording\nclass A"))
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
        val mediaRecorderUse = Regex("""\bMediaRecorder\b(?!\.AudioSource\b)|\bMediaMuxer\b|\bMediaCodec\b""")
        val storageUse = Regex("""\bStorageReference\b|\bcom\.google\.firebase\.storage\b""")
        val tunerTypes = """\b(AudioInputSource|AudioFrame|AudioRecordSource|PcmRecorder\w*)\b"""
        val tunerRef = Regex(tunerTypes + """|\bfeature\.(tuner|metronome)\b""")
        val recordingRef = Regex("""\bfeature\.(recording|video)\b""")
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

        fun storageViolations(source: String): List<String> =
            storageUse.findAll(strip(source)).map { "usa ${it.value}" }.distinct().toList()

        fun tunerReferenceViolations(source: String): List<String> =
            tunerRef.findAll(strip(source)).map { "referencia ${it.value}" }.distinct().toList()

        fun recordingReferenceViolations(source: String): List<String> =
            recordingRef.findAll(strip(source)).map { "referencia ${it.value}" }.distinct().toList()

        /** Solo `MediaRecorder.AudioSource` (en `AndroidPcmRecorderFactory`, para elegir la fuente de AudioRecord). */
        fun mediaRecorderViolations(source: String): List<String> {
            val code = strip(source).replace(mediaRecorderImport, "")
            return mediaRecorderUse.findAll(code).map { "usa ${it.value}" }.distinct().toList()
        }
    }
}
