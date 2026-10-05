package com.violinstudio.domain.testing

import com.violinstudio.domain.feature.tuner.audio.AudioOutput
import com.violinstudio.domain.feature.tuner.audio.PcmGenerator
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Salida fake: pide [blockSize] muestras cada [BLOCK_INTERVAL_MS] ms virtuales, las captura y emite los frames "reproducidos". */
class FakeAudioOutput(private val blockSize: Int = 1024, private val failure: Throwable? = null) : AudioOutput {
    private val captured = java.util.Collections.synchronizedList(mutableListOf<Float>())
    private val activeCount = AtomicInteger()
    private val maxActiveCount = AtomicInteger()
    private val releaseCount = AtomicInteger()

    val written: FloatArray get() = synchronized(captured) { captured.toFloatArray() }
    val active: Int get() = activeCount.get()
    val maxActive: Int get() = maxActiveCount.get()
    val releases: Int get() = releaseCount.get()

    override fun play(generator: PcmGenerator): Flow<Long> = flow {
        maxActiveCount.accumulateAndGet(activeCount.incrementAndGet(), ::maxOf)
        try {
            failure?.let { throw it }
            var position = 0L
            val buffer = FloatArray(blockSize)
            while (true) {
                generator.fill(buffer, position)
                captured.addAll(buffer.toList())
                position += blockSize
                emit(position)
                delay(BLOCK_INTERVAL_MS)
            }
        } finally {
            activeCount.decrementAndGet()
            releaseCount.incrementAndGet()
        }
    }

    companion object {
        const val BLOCK_INTERVAL_MS = 20L
    }
}
