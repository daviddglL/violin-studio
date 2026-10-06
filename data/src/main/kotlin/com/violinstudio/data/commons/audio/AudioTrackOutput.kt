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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Unica salida de audio de la app (tono y metronomo): un `AudioTrack` en stream, bucle `fill -> write` en
 * [dispatcher] (hilo dedicado, la escritura bloquea) y muestreo de la posicion REPRODUCIDA cada 20 ms.
 *
 * - Las reproducciones se serializan con un [Mutex]: nunca hay dos pistas abiertas.
 * - Crear la pista y `pause`/`flush`/`release` van en `Dispatchers.IO` (nunca en el hilo del colector ni en el hilo
 *   de audio, que puede estar atascado en un `write`).
 * - Cancelar: el escritor entrega el bloque de [PcmGenerator.finish], se espera (max [CLOSE_TIMEOUT_MS]) a que la
 *   cabeza de reproduccion lo alcance y se libera. Si el `write` esta atascado, vence el limite y `release` lo
 *   desbloquea: la liberacion ocurre ANTES de esperar al escritor, asi que cancelar nunca se queda colgado.
 * - Foco de audio: se pide (transitorio) antes de crear la pista; si se deniega no suena y llega como fallo. Al
 *   perderlo ([AudioFocus]) se cierra como al cancelar y el flujo termina sin error, sin reanudar al recuperarlo.
 *   El foco se abandona siempre una vez, despues de liberar la pista.
 * - Los fallos (crear, `play`, `fill`, `write`) llegan como [TunerFailure.AudioOutputUnavailable]; los errores
 *   posteriores a la cancelacion se ignoran.
 */
@Singleton
class AudioTrackOutput @Inject constructor(
    private val factory: PcmTrackFactory,
    private val focus: AudioFocus,
    @param:AudioOutputDispatcher private val dispatcher: CoroutineDispatcher
) : AudioOutput {
    private val playLock = Mutex()

    private class Session(val track: PcmTrack) {
        @Volatile var closing = false

        @Volatile var failed = false

        @Volatile var written = 0L
        private val released = AtomicBoolean(false)

        val isReleased: Boolean get() = released.get()

        /** Guarda compartida por la ruta de cancelacion y la de error: `pause`, `flush` y `release` una sola vez. */
        fun releaseOnce() {
            if (released.compareAndSet(false, true)) {
                runCatching { track.pause() }
                runCatching { track.flush() }
                runCatching { track.release() }
            }
        }
    }

    override fun play(generator: PcmGenerator): Flow<Long> = channelFlow {
        playLock.withLock {
            // Un callback de foco corre en otro hilo: solo completa esto (nunca toca la pista ni bloquea).
            val lost = CompletableDeferred<Unit>()
            val lease = withContext(Dispatchers.IO) { focus.request { lost.complete(Unit) } }
                ?: throw TunerFailure.AudioOutputUnavailable
            try {
                val session = Session(withContext(Dispatchers.IO) { open() })
                try {
                    coroutineScope {
                        val writer = launch(dispatcher) { writeLoop(session, generator) }
                        val position = PlaybackPosition()
                        try {
                            // Perder el foco termina el flujo con normalidad (no es un error) y no se reanuda.
                            while (withTimeoutOrNull(POSITION_INTERVAL_MS) { lost.await() } == null) {
                                send(position.update(session.track.playbackHeadPosition()))
                            }
                        } finally {
                            withContext(NonCancellable + Dispatchers.IO) { close(session, writer, position) }
                        }
                    }
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) { session.releaseOnce() }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { lease.abandon() }
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

    /** Pide el cierre, espera (acotado) a que suene y libera: antes de que el ambito espere al escritor. */
    private suspend fun close(session: Session, writer: Job, position: PlaybackPosition) {
        session.closing = true
        withTimeoutOrNull(CLOSE_TIMEOUT_MS) {
            writer.join()
            while (!session.failed && position.update(session.track.playbackHeadPosition()) < session.written) {
                delay(DRAIN_POLL_MS)
            }
        }
        session.releaseOnce()
    }

    /** Bloqueante, en el hilo de audio. No comprueba la cancelacion: usa `closing` para poder escribir el cierre. */
    private fun writeLoop(session: Session, generator: PcmGenerator) {
        val buffer = FloatArray(PcmFormat.BLOCK_SIZE)
        var start = 0L
        try {
            repeat(PREFILL_BLOCKS) {
                generator.fill(buffer, start)
                start = writeFully(session, buffer, start)
            }
            session.track.play()
            while (!session.closing) {
                generator.fill(buffer, start)
                start = writeFully(session, buffer, start)
            }
            if (generator.finish(buffer, start)) writeFully(session, buffer, start)
        } catch (e: Exception) {
            if (session.closing || session.isReleased) return
            session.failed = true
            throw TunerFailure.AudioOutputUnavailable
        }
    }

    /** Escribe el bloque entero (las escrituras pueden ser parciales); devuelve el indice del siguiente bloque. */
    private fun writeFully(session: Session, buffer: FloatArray, start: Long): Long {
        var offset = 0
        var zeroWrites = 0
        while (offset < buffer.size) {
            val n = session.track.write(buffer, offset, buffer.size - offset)
            when {
                n < 0 -> throw TunerFailure.AudioOutputUnavailable
                n == 0 -> if (++zeroWrites >= MAX_ZERO_WRITES) throw TunerFailure.AudioOutputUnavailable
                else -> {
                    zeroWrites = 0
                    offset += n
                }
            }
        }
        session.written = start + buffer.size
        return session.written
    }

    private companion object {
        const val POSITION_INTERVAL_MS = 20L
        const val DRAIN_POLL_MS = 5L
        const val CLOSE_TIMEOUT_MS = 300L
        const val PREFILL_BLOCKS = 2
        const val MAX_ZERO_WRITES = 50
    }
}
