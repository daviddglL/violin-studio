package com.violinstudio.data.feature.tuner.datasource.audio

import com.violinstudio.data.commons.di.AudioInputDispatcher
import com.violinstudio.data.feature.tuner.utils.AudioErrorMapper
import com.violinstudio.data.feature.tuner.utils.Pcm16
import com.violinstudio.domain.feature.tuner.audio.AudioInputSource
import com.violinstudio.domain.feature.tuner.failure.TunerFailure
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Captura en memoria: lee del micro en [dispatcher] (hilo dedicado, la lectura bloquea) y libera el
 * grabador siempre al terminar o cancelar. Sin ficheros ni logs.
 *
 * - Las capturas se serializan con un [Mutex] (la fuente es singleton): una coleccion nueva espera a que
 *   la anterior haya hecho `stop` y `release`, asi nunca hay dos grabadores vivos ni se encolan lecturas
 *   en el hilo unico. El `stop` de cancelacion no toma el mutex, por lo que no hay interbloqueo.
 * - Cancelar no interrumpe un `read` bloqueado: un vigilante en otro hilo llama a `stop()`, que lo desbloquea.
 * - Los chunks pueden ser mas cortos que `chunkSize` si `read` devuelve lecturas parciales.
 * - Un consumidor lento descarta el audio mas antiguo (cola de 2 chunks) en vez de acumular latencia.
 */
@Singleton
class AudioRecordSource @Inject constructor(
    private val factory: PcmRecorderFactory,
    private val permission: MicPermission,
    @param:AudioInputDispatcher private val dispatcher: CoroutineDispatcher
) : AudioInputSource {
    private val captureLock = Mutex()

    override fun frames(chunkSize: Int): Flow<FloatArray> = flow {
        captureLock.withLock {
            val recorder = open(chunkSize)
            val stopped = AtomicBoolean(false)
            val stopOnce = { if (stopped.compareAndSet(false, true)) runCatching { recorder.stop() } }
            try {
                coroutineScope {
                    // ATOMIC: si se cancela antes de que arranque, el cuerpo debe ejecutarse igualmente (su finally hace stop);
                    // con el arranque por defecto no corre y un read bloqueado no se desbloquea nunca.
                    val watcher = launch(Dispatchers.Default, start = CoroutineStart.ATOMIC) {
                        try {
                            awaitCancellation()
                        } finally {
                            stopOnce()
                        }
                    }
                    try {
                        recorder.start()
                        readLoop(recorder, chunkSize) { emit(it) }
                    } finally {
                        watcher.cancel()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw AudioErrorMapper.fromException(e)
            } finally {
                stopOnce()
                runCatching { recorder.release() }
            }
        }
    }.flowOn(dispatcher).buffer(capacity = QUEUE_CHUNKS, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private fun open(chunkSize: Int): PcmRecorder {
        if (!permission.isGranted()) throw TunerFailure.MicPermissionDenied
        return try {
            factory.create(chunkSize)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AudioErrorMapper.fromException(e)
        }
    }

    private suspend fun readLoop(recorder: PcmRecorder, chunkSize: Int, emit: suspend (FloatArray) -> Unit) {
        val buffer = ShortArray(chunkSize)
        var zeroReads = 0
        while (true) {
            val read = recorder.read(buffer)
            currentCoroutineContext().ensureActive()
            AudioErrorMapper.fromReadResult(read)?.let { throw it }
            if (read == 0) {
                if (++zeroReads >= MAX_ZERO_READS) throw TunerFailure.MicUnavailable
            } else {
                zeroReads = 0
                emit(Pcm16.toFloats(buffer, read))
            }
        }
    }

    private companion object {
        const val MAX_ZERO_READS = 50
        const val QUEUE_CHUNKS = 2
    }
}
