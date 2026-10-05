package com.violinstudio.data.feature.tuner.utils

import com.violinstudio.domain.feature.tuner.failure.TunerFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class AudioErrorMapperTest {
    @Test
    fun `SecurityException es permiso denegado`() {
        assertSame(TunerFailure.MicPermissionDenied, AudioErrorMapper.fromException(SecurityException("x")))
    }

    @Test
    fun `un TunerFailure se conserva y otra excepcion es microfono no disponible`() {
        assertSame(TunerFailure.MicBusy, AudioErrorMapper.fromException(TunerFailure.MicBusy))
        assertSame(TunerFailure.MicUnavailable, AudioErrorMapper.fromException(IllegalStateException()))
    }

    @Test
    fun `estado no inicializado es no disponible`() {
        assertNull(AudioErrorMapper.fromInitState(AudioErrorMapper.STATE_INITIALIZED))
        assertSame(TunerFailure.MicUnavailable, AudioErrorMapper.fromInitState(0))
    }

    @Test
    fun `si no esta grabando tras empezar es ocupado`() {
        assertNull(AudioErrorMapper.fromRecordingState(AudioErrorMapper.RECORDSTATE_RECORDING))
        assertSame(TunerFailure.MicBusy, AudioErrorMapper.fromRecordingState(1))
    }

    @Test
    fun `lecturas correctas no fallan y los errores de operacion o objeto muerto son ocupado`() {
        assertNull(AudioErrorMapper.fromReadResult(0))
        assertNull(AudioErrorMapper.fromReadResult(1024))
        assertSame(TunerFailure.MicBusy, AudioErrorMapper.fromReadResult(AudioErrorMapper.ERROR_INVALID_OPERATION))
        assertSame(TunerFailure.MicBusy, AudioErrorMapper.fromReadResult(AudioErrorMapper.ERROR_DEAD_OBJECT))
    }

    @Test
    fun `otros codigos negativos son no disponible`() {
        assertEquals(TunerFailure.MicUnavailable, AudioErrorMapper.fromReadResult(-1))
        assertEquals(TunerFailure.MicUnavailable, AudioErrorMapper.fromReadResult(-2))
    }
}

class AudioErrorMapperGuardsTest {
    @Test
    fun `minBuffer no positivo es no disponible`() {
        assertNull(AudioErrorMapper.fromMinBuffer(3528))
        assertSame(TunerFailure.MicUnavailable, AudioErrorMapper.fromMinBuffer(-2))
        assertSame(TunerFailure.MicUnavailable, AudioErrorMapper.fromMinBuffer(0))
    }

    @Test
    fun `requireInitialized libera una vez y lanza si no esta inicializado`() {
        var released = 0
        val thrown = runCatching { AudioErrorMapper.requireInitialized(0) { released++ } }.exceptionOrNull()
        assertSame(TunerFailure.MicUnavailable, thrown)
        assertEquals(1, released)
        AudioErrorMapper.requireInitialized(AudioErrorMapper.STATE_INITIALIZED) { released++ }
        assertEquals(1, released)
    }

    @Test
    fun `requireRecording lanza ocupado si no graba`() {
        AudioErrorMapper.requireRecording(AudioErrorMapper.RECORDSTATE_RECORDING)
        assertSame(TunerFailure.MicBusy, runCatching { AudioErrorMapper.requireRecording(1) }.exceptionOrNull())
    }
}
