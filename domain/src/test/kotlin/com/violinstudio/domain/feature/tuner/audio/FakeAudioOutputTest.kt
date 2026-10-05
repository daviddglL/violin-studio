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

    @Test
    fun `un generador sin bloque de cierre no escribe nada al cancelar`() = runTest {
        val output = FakeAudioOutput(blockSize = 4)
        assertEquals(false, ramp.finish(FloatArray(4), 0))
        output.play(ramp).take(1).toList()
        assertEquals(4, output.written.size)
    }

    @Test
    fun `al cancelar escribe el bloque de cierre del generador`() = runTest {
        val output = FakeAudioOutput(blockSize = 4)
        val closing = object : PcmGenerator {
            override fun fill(buffer: FloatArray, startSample: Long) = buffer.fill(1f)

            override fun finish(buffer: FloatArray, startSample: Long): Boolean {
                buffer.fill(0f)
                return true
            }
        }
        output.play(closing).take(1).toList()
        assertEquals(listOf(1f, 1f, 1f, 1f, 0f, 0f, 0f, 0f), output.written.toList())
    }
}
