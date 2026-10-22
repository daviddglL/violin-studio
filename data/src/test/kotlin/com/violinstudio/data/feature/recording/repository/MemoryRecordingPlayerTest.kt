package com.violinstudio.data.feature.recording.repository

import com.violinstudio.data.feature.recording.datasource.media.AudioClipOutput
import com.violinstudio.data.feature.recording.datasource.media.ByteArrayDataSource
import com.violinstudio.data.feature.recording.datasource.media.RecordingBytesSource
import com.violinstudio.domain.feature.recording.model.MediaKind
import com.violinstudio.domain.feature.recording.model.PlaybackState
import com.violinstudio.domain.feature.recording.model.RecordingLimits
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryRecordingPlayerTest {
    private class FakeOutput : AudioClipOutput {
        var played = mutableListOf<ByteArray>()
        var stops = 0
        var onDone: () -> Unit = {}
        var onError: () -> Unit = {}
        var fail = false

        override fun play(bytes: ByteArray, onDone: () -> Unit, onError: () -> Unit) {
            if (fail) throw IOException("decoder")
            played += bytes
            this.onDone = onDone
            this.onError = onError
        }

        override fun stop() {
            stops++
        }
    }

    private class FakeBytes : RecordingBytesSource {
        val pending = mutableMapOf<String, CompletableDeferred<ByteArray>>()
        val limits = mutableMapOf<String, Long>()

        override suspend fun read(objectPath: String, maxBytes: Long): ByteArray {
            limits[objectPath] = maxBytes
            return pending.getOrPut(objectPath) { CompletableDeferred() }.await()
        }
    }

    private val output = FakeOutput()
    private val bytes = FakeBytes()

    @Test
    fun `descarga, reproduce y vuelve a reposo al terminar`() = runTest {
        val player = MemoryRecordingPlayer(bytes, output, StandardTestDispatcher(testScheduler))
        launch { player.play("r1", "p1") }
        runCurrent()
        assertEquals(PlaybackState.Loading("r1"), player.state.value)
        bytes.pending.getValue("p1").complete(byteArrayOf(1, 2))
        runCurrent()
        assertEquals(PlaybackState.Playing("r1"), player.state.value)
        assertArrayEquals(byteArrayOf(1, 2), output.played.single())
        output.onDone()
        assertEquals(PlaybackState.Idle, player.state.value)
    }

    @Test
    fun `una descarga que llega tarde no suena encima de otra y parar invalida`() = runTest {
        val player = MemoryRecordingPlayer(bytes, output, StandardTestDispatcher(testScheduler))
        launch { player.play("r1", "p1") }
        runCurrent()
        launch { player.play("r2", "p2") }
        runCurrent()
        bytes.pending.getValue("p1").complete(byteArrayOf(1))
        runCurrent()
        assertEquals(PlaybackState.Loading("r2"), player.state.value)
        assertEquals(0, output.played.size)
        bytes.pending.getValue("p2").complete(byteArrayOf(2))
        runCurrent()
        val oldDone = output.onDone
        player.stop()
        oldDone()
        assertEquals(PlaybackState.Idle, player.state.value)
    }

    @Test
    fun `fallos de descarga, de decodificador o del reproductor quedan como Failed`() = runTest {
        val player = MemoryRecordingPlayer(bytes, output, StandardTestDispatcher(testScheduler))
        launch { player.play("r1", "p1") }
        runCurrent()
        bytes.pending.getValue("p1").completeExceptionally(IOException("red"))
        runCurrent()
        assertEquals(PlaybackState.Failed("r1"), player.state.value)
        output.fail = true
        launch { player.play("r2", "p2") }
        runCurrent()
        bytes.pending.getValue("p2").complete(byteArrayOf(1))
        runCurrent()
        assertEquals(PlaybackState.Failed("r2"), player.state.value)
        output.fail = false
        launch { player.play("r3", "p3") }
        runCurrent()
        bytes.pending.getValue("p3").complete(byteArrayOf(1))
        runCurrent()
        output.onError()
        assertEquals(PlaybackState.Failed("r3"), player.state.value)
    }

    @Test
    fun `un video se descarga con su tope y queda para la pantalla sin sonar`() = runTest {
        val player = MemoryRecordingPlayer(bytes, output, StandardTestDispatcher(testScheduler))
        launch { player.play("r1", "p1") }
        runCurrent()
        assertEquals(RecordingLimits.MAX_AUDIO_BYTES, bytes.limits["p1"])
        launch { player.play("v1", "pv", MediaKind.VIDEO) }
        runCurrent()
        assertEquals(RecordingLimits.MAX_VIDEO_BYTES, bytes.limits["pv"])
        assertEquals(PlaybackState.Loading("v1"), player.state.value)
        bytes.pending.getValue("pv").complete(byteArrayOf(7, 8))
        runCurrent()
        val video = player.state.value as PlaybackState.Video
        assertEquals("v1", video.recordingId)
        assertArrayEquals(byteArrayOf(7, 8), video.clip.bytes)
        assertEquals(0, output.played.size)
        player.stop()
        assertEquals(PlaybackState.Idle, player.state.value)
    }

    @Test
    fun `un video que llega tarde o falla no pisa lo demas`() = runTest {
        val player = MemoryRecordingPlayer(bytes, output, StandardTestDispatcher(testScheduler))
        launch { player.play("v1", "pv", MediaKind.VIDEO) }
        runCurrent()
        player.stop()
        bytes.pending.getValue("pv").complete(byteArrayOf(1))
        runCurrent()
        assertEquals(PlaybackState.Idle, player.state.value)
        launch { player.play("v2", "pv2", MediaKind.VIDEO) }
        runCurrent()
        bytes.pending.getValue("pv2").completeExceptionally(IOException("demasiado grande"))
        runCurrent()
        assertEquals(PlaybackState.Failed("v2"), player.state.value)
    }

    @Test
    fun `cancelar la descarga vuelve a reposo`() = runTest {
        val player = MemoryRecordingPlayer(bytes, output, StandardTestDispatcher(testScheduler))
        val job = launch { player.play("r1", "p1") }
        runCurrent()
        job.cancel()
        runCurrent()
        assertEquals(PlaybackState.Idle, player.state.value)
    }

    @Test
    fun `la fuente en memoria lee por trozos y marca el final`() {
        val source = ByteArrayDataSource(byteArrayOf(1, 2, 3))
        val buffer = ByteArray(4)
        assertEquals(3L, source.size)
        assertEquals(2, source.readAt(1, buffer, 0, 4))
        assertArrayEquals(byteArrayOf(2, 3, 0, 0), buffer)
        assertEquals(-1, source.readAt(3, buffer, 0, 4))
        assertEquals(-1, source.readAt(-1, buffer, 0, 4))
        source.close()
    }
}
