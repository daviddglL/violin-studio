package com.violinstudio.domain.testing

import com.violinstudio.domain.feature.tuner.audio.AudioInputSource
import kotlin.math.PI
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Fuente fake: [chunk] produce el chunk numero `index` de `size` muestras. Emite [limit] chunks
 * (sin limite hasta cancelar) y despues lanza [failure] si existe.
 */
class FakeAudioInputSource(
    private val limit: Int? = null,
    private val failure: Throwable? = null,
    private val chunk: (index: Int, size: Int) -> FloatArray
) : AudioInputSource {
    override fun frames(chunkSize: Int): Flow<FloatArray> = flow {
        var index = 0
        while (limit == null || index < limit) {
            emit(chunk(index, chunkSize))
            delay(CHUNK_INTERVAL_MS)
            index++
        }
        failure?.let { throw it }
    }

    companion object {
        /** Pausa virtual entre chunks: la fuente suspende y la cancelacion es cooperativa. */
        const val CHUNK_INTERVAL_MS = 1L

        /** Seno continuo (la fase avanza entre chunks). */
        fun sine(frequency: Double, limit: Int? = null, failure: Throwable? = null) =
            FakeAudioInputSource(limit, failure) { index, size ->
                Signals.sine(frequency, size, phase = 2 * PI * frequency * index.toLong() * size / Signals.SAMPLE_RATE)
            }

        fun silence(limit: Int? = null) = FakeAudioInputSource(limit) { _, size -> Signals.silence(size) }
    }
}
