package com.violinstudio.domain.feature.tuner.audio

import com.violinstudio.domain.testing.FakeAudioOutput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakeAudioOutputTest {
    private val ramp = PcmGenerator { buffer, start -> for (i in buffer.indices) buffer[i] = (start + i).toFloat() }

    @Test
    fun `captura las muestras con indices contiguos y emite los frames reproducidos`() = runTest {
        val output = FakeAudioOutput(blockSize = 4)
        val positions = output.play(ramp).take(3).toList()
        assertEquals(listOf(4L, 8L, 12L), positions)
        assertEquals((0 until 12).map { it.toFloat() }, output.written.toList())
    }

    @Test
    fun `al cancelar libera una vez y no queda salida activa`() = runTest {
        val output = FakeAudioOutput()
        val job = launch { output.play(ramp).collect { } }
        runCurrent()
        assertEquals(1, output.active)
        advanceTimeBy(100)
        job.cancel()
        runCurrent()
        assertEquals(0, output.active)
        assertEquals(1, output.releases)
        assertTrue(output.written.isNotEmpty())
    }
}
