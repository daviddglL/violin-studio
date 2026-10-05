package com.violinstudio.data.feature.tuner.datasource.audio

import com.violinstudio.data.commons.di.AudioInputDispatcher
import com.violinstudio.data.feature.tuner.utils.AudioErrorMapper
import com.violinstudio.data.feature.tuner.utils.Pcm16
import com.violinstudio.domain.feature.tuner.audio.AudioInputSource
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Captura en memoria: lee del micro en [dispatcher] (hilo dedicado, la lectura bloquea) y libera el
 * grabador siempre al terminar o cancelar. Sin ficheros ni logs.
 */
class AudioRecordSource @Inject constructor(
    private val factory: PcmRecorderFactory,
    @param:AudioInputDispatcher private val dispatcher: CoroutineDispatcher
) : AudioInputSource {
    override fun frames(chunkSize: Int): Flow<FloatArray> = flow {
        val recorder = try {
            factory.create(chunkSize)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AudioErrorMapper.fromException(e)
        }
        try {
            recorder.start()
            val buffer = ShortArray(chunkSize)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = recorder.read(buffer)
                AudioErrorMapper.fromReadResult(read)?.let { throw it }
                if (read > 0) emit(Pcm16.toFloats(buffer, read))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AudioErrorMapper.fromException(e)
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
    }.flowOn(dispatcher)
}
