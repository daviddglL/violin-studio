package com.violinstudio.domain.feature.tuner.usecase

import app.cash.turbine.Event
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.tuner.failure.TunerFailure
import com.violinstudio.domain.feature.tuner.model.Note
import com.violinstudio.domain.feature.tuner.model.ReferencePitch
import com.violinstudio.domain.feature.tuner.model.TunerConfig
import com.violinstudio.domain.feature.tuner.model.TunerReading
import com.violinstudio.domain.feature.tuner.model.TuningTarget
import com.violinstudio.domain.testing.FakeAudioInputSource
import kotlin.math.pow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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

    private fun TestScope.useCase(source: FakeAudioInputSource) =
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
    fun `consumidor lento recibe menos lecturas y la ultima`() = runTest {
        val received = mutableListOf<TunerReading>()
        useCase(FakeAudioInputSource.sine(440.0, limit = 1000))
            .invoke(Instrument.VIOLIN, TunerConfig(), null)
            .collect {
                received += it
                delay(500)
            }
        assertTrue(received.size < 1000 / 4) { "conflate must drop stale readings, got ${received.size}" }
        assertInstanceOf(TunerReading.Pitch::class.java, received.last())
    }
}

private suspend fun ReceiveTurbine<TunerReading>.failure(): Throwable {
    while (true) {
        val event = awaitEvent()
        if (event is Event.Error) return event.throwable
        if (event is Event.Complete) throw AssertionError("completed without failure")
    }
}
