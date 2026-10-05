package com.violinstudio.data.feature.tuner.utils

import android.media.AudioRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Las constantes de AudioRecord se inlinean en compilacion: no hace falta framework. */
class AudioRecordConstantsTest {
    @Test
    fun `las constantes copiadas coinciden con las de AudioRecord`() {
        assertEquals(AudioRecord.STATE_INITIALIZED, AudioErrorMapper.STATE_INITIALIZED)
        assertEquals(AudioRecord.RECORDSTATE_RECORDING, AudioErrorMapper.RECORDSTATE_RECORDING)
        assertEquals(AudioRecord.ERROR_INVALID_OPERATION, AudioErrorMapper.ERROR_INVALID_OPERATION)
        assertEquals(AudioRecord.ERROR_DEAD_OBJECT, AudioErrorMapper.ERROR_DEAD_OBJECT)
    }
}
