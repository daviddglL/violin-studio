package com.violinstudio.data.feature.tuner.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AudioRecordConfigTest {
    @Test
    fun `usa UNPROCESSED solo si el dispositivo lo soporta`() {
        assertEquals(CaptureSource.UNPROCESSED, AudioRecordConfig.sourceFor(unprocessedSupported = true))
        assertEquals(CaptureSource.VOICE_RECOGNITION, AudioRecordConfig.sourceFor(unprocessedSupported = false))
    }

    @Test
    fun `el buffer es el maximo entre el minimo y 4 chunks de 2 bytes`() {
        assertEquals(8192, AudioRecordConfig.bufferBytes(minBufferBytes = 1000, chunkSize = 1024))
        assertEquals(20000, AudioRecordConfig.bufferBytes(minBufferBytes = 20000, chunkSize = 1024))
    }

    @Test
    fun `44 kHz mono`() {
        assertEquals(44_100, AudioRecordConfig.SAMPLE_RATE)
    }
}
