package com.violinstudio.domain.feature.tuner.usecase

import app.cash.turbine.Event
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.tuner.audio.AudioInputSource
import com.violinstudio.domain.feature.tuner.failure.TunerFailure
import com.violinstudio.domain.feature.tuner.model.Note
import com.violinstudio.domain.feature.tuner.model.ReferencePitch
import com.violinstudio.domain.feature.tuner.model.TunerConfig
import com.violinstudio.domain.feature.tuner.model.TunerReading
import com.violinstudio.domain.feature.tuner.model.TuningTarget
import com.violinstudio.domain.feature.tuner.pitch.DetectorProfile
import com.violinstudio.domain.feature.tuner.pitch.PitchDetector
import com.violinstudio.domain.feature.tuner.pitch.PitchEstimate
import com.violinstudio.domain.testing.FakeAudioInputSource
import com.violinstudio.domain.testing.Signals
import kotlin.math.pow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ObservePitchUseCaseTest {
    private val a4Plus5 = 440.0 * 2.0.pow(5.0 / 1200)

    private fun TestScope.useCase(source: AudioInputSource) =
        ObservePitchUseCase(source, dispatcher = StandardTestDispatcher(testScheduler))

    @Test
    fun `A4 mas 5 cents converge a Pitch A4 con 5 cents`() = runTest {
        val readings = useCase(FakeAudioInputSource.sine(a4Plus5, limit = 12))
            .invoke(Instrument.VIOLIN, TunerConfig(), null).toList()
        val last = assertInstanceOf(TunerReading.Pitch::class.java, readings.last())
        val target = assertInstanceOf(TuningTarget.OpenString::class.java, last.target)
        assertEquals(Note(69), target.note)
        assertEquals(5.0, last.cents, 2.0)
    }

    @Test
    fun `silencio emite NoPitch`() = runTest {
        val readings = useCase(FakeAudioInputSource.silence(limit = 8))
            .invoke(Instrument.VIOLIN, TunerConfig(), null).toList()
        assertTrue(readings.isNotEmpty() && readings.all { it == TunerReading.NoPitch })
    }

    @Test
    fun `usa la referencia configurada`() = runTest {
        val config = TunerConfig(referencePitch = ReferencePitch(442.0))
        val readings = useCase(FakeAudioInputSource.sine(442.0, limit = 12))
            .invoke(Instrument.VIOLIN, config, null).toList()
        assertEquals(0.0, (readings.last() as TunerReading.Pitch).cents, 2.0)
    }

    @Test
    fun `la cuerda seleccionada fija el objetivo`() = runTest {
        val readings = useCase(FakeAudioInputSource.sine(440.0, limit = 12))
            .invoke(Instrument.VIOLIN, TunerConfig(), 1).toList()
        val target = (readings.last() as TunerReading.Pitch).target as TuningTarget.OpenString
        assertEquals(1, target.index)
    }

    @Test
    fun `una excepcion de la fuente llega como TunerFailure`() = runTest {
        val unexpected = FakeAudioInputSource.sine(440.0, limit = 3, failure = IllegalStateException("x"))
        useCase(unexpected).invoke(Instrument.VIOLIN, TunerConfig(), null).test {
            assertEquals(TunerFailure.MicUnavailable, failure())
        }
        val busy = FakeAudioInputSource.sine(440.0, limit = 3, failure = TunerFailure.MicBusy)
        useCase(busy).invoke(Instrument.VIOLIN, TunerConfig(), null).test {
            assertEquals(TunerFailure.MicBusy, failure())
        }
    }

    @Test
    fun `consumidor lento recibe pocas lecturas y la ultima`() = runTest {
        var calls = 0
        val counting = object : PitchDetector {
            override fun detect(frame: FloatArray) = PitchEstimate(200.0 + calls++, 0.9)
        }
        val received = mutableListOf<TunerReading.Pitch>()
        ObservePitchUseCase(
            FakeAudioInputSource.sine(440.0, limit = 1000),
            StandardTestDispatcher(testScheduler),
            detectorFactory = { counting }
        ).invoke(Instrument.VIOLIN, TunerConfig(), null).collect {
            received += it as TunerReading.Pitch
            delay(500)
        }
        assertTrue(received.size <= 10) { "conflate must drop stale readings, got ${received.size}" }
        // mediana de las 5 ultimas estimaciones: la ultima lectura corresponde al ultimo frame
        assertEquals(200.0 + (calls - 1) - 2, received.last().frequency, 1e-6)
    }

    @Test
    fun `el hold se deriva del perfil y el tono persiste unos 300 ms`() {
        assertEquals(13, ObservePitchUseCase.holdFrames(DetectorProfile.of(Instrument.VIOLIN)))
        assertEquals(7, ObservePitchUseCase.holdFrames(DetectorProfile.of(Instrument.CELLO)))
    }

    @Test
    fun `tras el seno el tono persiste el hold y luego NoPitch`() = runTest {
        val sineChunks = 10
        val source = FakeAudioInputSource(limit = 40) { index, size ->
            if (index < sineChunks) {
                Signals.sine(440.0, size, phase = 2 * Math.PI * 440.0 * index * size / Signals.SAMPLE_RATE)
            } else {
                Signals.silence(size)
            }
        }
        val readings = useCase(source).invoke(Instrument.VIOLIN, TunerConfig(), null).toList()
        val pitches = readings.takeWhile { it is TunerReading.Pitch }.size
        val hold = ObservePitchUseCase.holdFrames(DetectorProfile.of(Instrument.VIOLIN))
        // frames con seno completo: sineChunks - 1 (el siguiente, medio seno, puede o no detectarse)
        assertTrue(pitches in (sineChunks - 1 + hold)..(sineChunks + hold)) { "pitches=$pitches" }
        assertTrue(readings.drop(pitches).all { it == TunerReading.NoPitch } && readings.size > pitches)
    }

    @Test
    fun `CancellationException ajena y Error no se convierten en TunerFailure`() = runTest {
        val cancel = FakeAudioInputSource.sine(440.0, limit = 2, failure = CancellationException("timeout"))
        val c = runCatching { useCase(cancel).invoke(Instrument.VIOLIN, TunerConfig(), null).toList() }
        assertInstanceOf(CancellationException::class.java, c.exceptionOrNull())
        val boom = FakeAudioInputSource.sine(440.0, limit = 2, failure = StackOverflowError())
        val e = runCatching { useCase(boom).invoke(Instrument.VIOLIN, TunerConfig(), null).toList() }
        assertInstanceOf(StackOverflowError::class.java, e.exceptionOrNull())
    }

    @Test
    fun `la fuente se libera al cancelar`() = runTest {
        var released = false
        val source = object : AudioInputSource {
            override fun frames(chunkSize: Int) = flow {
                try {
                    while (true) {
                        emit(FloatArray(chunkSize))
                        delay(1)
                    }
                } finally {
                    released = true
                }
            }
        }
        useCase(source).invoke(Instrument.VIOLIN, TunerConfig(), null).take(2).toList()
        assertTrue(released)
    }

    @Test
    fun `dos colecciones concurrentes tienen estado independiente`() = runTest {
        val uc = useCase(FakeAudioInputSource.sine(a4Plus5, limit = 12))
        val solo = uc(Instrument.VIOLIN, TunerConfig(), null).toList()
        val both = listOf(
            async { uc(Instrument.VIOLIN, TunerConfig(), null).toList() },
            async { uc(Instrument.VIOLIN, TunerConfig(), null).toList() }
        ).awaitAll()
        assertEquals(solo, both[0])
        assertEquals(solo, both[1])
    }
}

private suspend fun ReceiveTurbine<TunerReading>.failure(): Throwable {
    while (true) {
        val event = awaitEvent()
        if (event is Event.Error) return event.throwable
        if (event is Event.Complete) throw AssertionError("completed without failure")
    }
}
