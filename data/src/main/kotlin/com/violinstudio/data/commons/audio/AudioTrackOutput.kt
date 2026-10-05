package com.violinstudio.data.commons.audio

import com.violinstudio.data.commons.di.AudioOutputDispatcher
import com.violinstudio.domain.feature.tuner.audio.AudioOutput
import com.violinstudio.domain.feature.tuner.audio.PcmFormat
import com.violinstudio.domain.feature.tuner.audio.PcmGenerator
import com.violinstudio.domain.feature.tuner.failure.TunerFailure
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Unica salida de audio de la app (tono y metronomo): un `AudioTrack` en stream, bucle `fill -> write` en
 * [dispatcher] (hilo dedicado, la escritura bloquea) y muestreo de la posicion REPRODUCIDA cada 20 ms.
 *
 * - Las reproducciones se serializan con un [Mutex]: nunca hay dos pistas abiertas; una nueva espera a que la
 *   anterior haya hecho `pause`, `flush` y `release`.
 * - Con la pista sonando, `write` solo bloquea hasta que haya hueco (< duracion del buffer), asi que la
 *   cancelacion se nota en la siguiente vuelta del bucle; la pista se libera una sola vez al salir del ambito.
 * - Los fallos (crear la pista o escribir) llegan como [TunerFailure.AudioOutputUnavailable].
 */
@Singleton
class AudioTrackOutput @Inject constructor(
    private val factory: PcmTrackFactory,
    @param:AudioOutputDispatcher private val dispatcher: CoroutineDispatcher
) : AudioOutput {
    private val playLock = Mutex()

    override fun play(generator: PcmGenerator): Flow<Long> = channelFlow {
        playLock.withLock {
            val track = open()
            val released = AtomicBoolean(false)
            try {
                coroutineScope {
                    track.play()
                    launch(dispatcher) { writeLoop(track, generator) }
                    val position = PlaybackPosition()
                    while (true) {
                        delay(POSITION_INTERVAL_MS)
                        send(position.update(track.playbackHeadPosition()))
                    }
                }
            } finally {
                if (released.compareAndSet(false, true)) {
                    runCatching { track.pause() }
                    runCatching { track.flush() }
                    runCatching { track.release() }
                }
            }
        }
    }

    private fun open(): PcmTrack = try {
        factory.create(PcmFormat.BLOCK_SIZE)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw TunerFailure.AudioOutputUnavailable
    }

    private suspend fun writeLoop(track: PcmTrack, generator: PcmGenerator) {
        val buffer = FloatArray(PcmFormat.BLOCK_SIZE)
        var start = 0L
        while (true) {
            generator.fill(buffer, start)
            if (track.write(buffer, buffer.size) < 0) throw TunerFailure.AudioOutputUnavailable
            currentCoroutineContext().ensureActive()
            start += buffer.size
        }
    }

    private companion object {
        const val POSITION_INTERVAL_MS = 20L
    }
}
