package com.violinstudio.data.feature.recording.repository

import com.violinstudio.data.feature.recording.datasource.media.AudioClipOutput
import com.violinstudio.data.feature.recording.datasource.media.RecordingBytesSource
import com.violinstudio.data.feature.recording.di.RecordingDispatcher
import com.violinstudio.domain.feature.recording.model.PlaybackState
import com.violinstudio.domain.feature.recording.repository.RecordingPlayer
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Descarga la grabacion a memoria y la reproduce; nunca escribe en disco. Una reproduccion a la vez: cada [play] o
 * [stop] invalida la anterior (un token), asi una descarga lenta que llega tarde no suena encima de otra.
 */
@Singleton
class MemoryRecordingPlayer @Inject constructor(
    private val bytes: RecordingBytesSource,
    private val output: AudioClipOutput,
    @RecordingDispatcher private val dispatcher: CoroutineDispatcher
) : RecordingPlayer {
    private val current = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    private val token = AtomicLong()

    override val state: StateFlow<PlaybackState> = current.asStateFlow()

    override suspend fun play(recordingId: String, objectPath: String) {
        val mine = token.incrementAndGet()
        withContext(dispatcher) { output.stop() }
        current.value = PlaybackState.Loading(recordingId)
        try {
            val audio = bytes.read(objectPath)
            if (token.get() != mine) return
            current.value = PlaybackState.Playing(recordingId)
            withContext(dispatcher) {
                output.play(
                    audio,
                    onDone = { if (token.get() == mine) current.value = PlaybackState.Idle },
                    onError = { if (token.get() == mine) current.value = PlaybackState.Failed(recordingId) }
                )
            }
        } catch (e: CancellationException) {
            if (token.get() == mine) current.value = PlaybackState.Idle
            throw e
        } catch (_: Exception) {
            if (token.get() == mine) current.value = PlaybackState.Failed(recordingId)
        }
    }

    override fun stop() {
        token.incrementAndGet()
        output.stop()
        current.value = PlaybackState.Idle
    }
}
