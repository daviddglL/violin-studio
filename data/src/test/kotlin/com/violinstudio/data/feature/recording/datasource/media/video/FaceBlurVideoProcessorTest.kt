package com.violinstudio.data.feature.recording.datasource.media.video

import com.violinstudio.domain.feature.recording.failure.RecordingFailure
import com.violinstudio.domain.feature.recording.model.RecordingLimits
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Video (4c): propiedad del fichero difuminado con un transcodificador fake (sin GL ni ML Kit). */
@OptIn(ExperimentalCoroutinesApi::class)
class FaceBlurVideoProcessorTest {
    @TempDir
    lateinit var root: File

    private val source by lazy { File(root, "raw.mp4").apply { writeBytes(ByteArray(64)) } }
    private val output by lazy { File(root, "blurred.mp4") }
    private val part by lazy { File(root, "blurred.mp4.part") }

    private class FakeTranscoder(
        private val bytes: Long = 1024,
        private val durationMs: Long = 30_000,
        private val failure: Exception? = null,
        private val gate: CompletableDeferred<Unit>? = null
    ) : BlurTranscoder {
        var calls = 0

        override suspend fun export(source: File, output: File): Long {
            calls++
            RandomAccessFile(output, "rw").use { it.setLength(bytes) }
            gate?.await()
            failure?.let { throw it }
            return durationMs
        }
    }

    @Test
    fun `exportacion correcta deja solo el fichero final con el sello de la politica`() = runTest {
        val result = FaceBlurVideoProcessor(FakeTranscoder()).blur(source.path, output.path).getOrThrow()
        assertEquals(output.path, result.path)
        assertEquals(30_000L, result.durationMs)
        assertEquals(1024L, result.sizeBytes)
        assertEquals(RecordingLimits.BLUR_POLICY_VERSION, result.policyVersion)
        assertTrue(output.isFile)
        assertFalse(part.exists())
        assertTrue(source.isFile, "el original lo borra quien llama")
    }

    @Test
    fun `si la exportacion falla no queda parcial ni fichero final`() = runTest {
        val result = FaceBlurVideoProcessor(FakeTranscoder(failure = IOException("gl"))).blur(source.path, output.path)
        assertEquals(RecordingFailure.BlurFailed, result.exceptionOrNull())
        assertFalse(part.exists())
        assertFalse(output.exists())
    }

    @Test
    fun `un resultado vacio o sin duracion es un fallo`() = runTest {
        val empty = FaceBlurVideoProcessor(FakeTranscoder(bytes = 0)).blur(source.path, output.path)
        assertEquals(RecordingFailure.BlurFailed, empty.exceptionOrNull())
        val noDuration = FaceBlurVideoProcessor(FakeTranscoder(durationMs = 0)).blur(source.path, output.path)
        assertEquals(RecordingFailure.BlurFailed, noDuration.exceptionOrNull())
        assertFalse(part.exists())
        assertFalse(output.exists())
    }

    @Test
    fun `60 MiB exactos se aceptan y uno mas se borra como demasiado grande`() = runTest {
        val max = RecordingLimits.MAX_VIDEO_BYTES
        val ok = FaceBlurVideoProcessor(FakeTranscoder(bytes = max)).blur(source.path, output.path)
        assertEquals(max, ok.getOrThrow().sizeBytes)
        val big = FaceBlurVideoProcessor(FakeTranscoder(bytes = max + 1)).blur(source.path, output.path)
        assertEquals(RecordingFailure.TooLarge, big.exceptionOrNull())
        assertFalse(part.exists())
        assertFalse(output.exists(), "un fallo tampoco deja el difuminado de una exportacion anterior")
    }

    @Test
    fun `sin original, o con la salida igual al original, falla sin llamar al transcodificador`() = runTest {
        val transcoder = FakeTranscoder()
        val processor = FaceBlurVideoProcessor(transcoder)
        val missing = processor.blur(File(root, "nope.mp4").path, output.path)
        assertEquals(RecordingFailure.BlurFailed, missing.exceptionOrNull())
        assertEquals(RecordingFailure.BlurFailed, processor.blur(source.path, source.path).exceptionOrNull())
        assertEquals(0, transcoder.calls)
        assertTrue(source.isFile)
    }

    @Test
    fun `cancelar a medias borra el parcial y propaga la cancelacion`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val job = async { FaceBlurVideoProcessor(FakeTranscoder(gate = gate)).blur(source.path, output.path) }
        runCurrent()
        assertTrue(part.exists())
        job.cancel(CancellationException("pantalla cerrada"))
        runCurrent()
        assertTrue(job.isCancelled)
        assertFalse(part.exists())
        assertFalse(output.exists())
    }

    @Test
    fun `un parcial viejo de otra exportacion se descarta antes de empezar`() = runTest {
        part.writeBytes(ByteArray(10))
        output.writeBytes(ByteArray(10))
        val result = FaceBlurVideoProcessor(FakeTranscoder(failure = IOException())).blur(source.path, output.path)
        assertTrue(result.isFailure)
        assertFalse(part.exists())
        assertFalse(output.exists())
    }
}
