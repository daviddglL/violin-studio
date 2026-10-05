package com.violinstudio.data.feature.tuner.utils

enum class CaptureSource { UNPROCESSED, VOICE_RECOGNITION }

/** Parametros de captura decididos sin framework (mono, PCM 16 bit). */
object AudioRecordConfig {
    const val SAMPLE_RATE = 44_100
    private const val BYTES_PER_SAMPLE = 2
    private const val CHUNKS_IN_BUFFER = 4

    fun sourceFor(unprocessedSupported: Boolean): CaptureSource =
        if (unprocessedSupported) CaptureSource.UNPROCESSED else CaptureSource.VOICE_RECOGNITION

    fun bufferBytes(minBufferBytes: Int, chunkSize: Int): Int =
        maxOf(minBufferBytes, CHUNKS_IN_BUFFER * chunkSize * BYTES_PER_SAMPLE)
}
