package com.violinstudio.domain.feature.tuner.audio

import app.cash.turbine.test
import com.violinstudio.domain.feature.tuner.failure.TunerFailure
import com.violinstudio.domain.testing.FakeAudioInputSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.runTest

class FakeAudioInputSourceTest {
    @Test
    fun `emite chunks del tamano pedido y continuos`() = runTest {
        val source = FakeAudioInputSource.sine(440.0)
        val chunks = source.frames(1024).take(3).toList()
        assertEquals(listOf(1024, 1024, 1024), chunks.map { it.size })
        val whole = FakeAudioInputSource.sine(440.0).frames(3072).take(1).toList().single()
        assertEquals(whole.toList(), chunks.flatMap { it.toList() })
    }

    @Test
    fun `termina al cancelar la coleccion`() = runTest {
        FakeAudioInputSource.sine(440.0).frames(512).test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `con limite completa y con fallo lo lanza`() = runTest {
        assertEquals(2, FakeAudioInputSource.sine(440.0, limit = 2).frames(64).toList().size)
        FakeAudioInputSource.sine(440.0, limit = 1, failure = TunerFailure.MicBusy).frames(64).test {
            awaitItem()
            assertEquals(TunerFailure.MicBusy, awaitError())
        }
    }
}
