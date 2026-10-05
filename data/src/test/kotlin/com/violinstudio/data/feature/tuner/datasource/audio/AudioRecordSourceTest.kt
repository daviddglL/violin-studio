package com.violinstudio.data.feature.tuner.datasource.audio

import app.cash.turbine.test
import com.violinstudio.domain.feature.tuner.failure.TunerFailure
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioRecordSourceTest {
    private class FakeRecorder(
        private val reads: MutableList<Int>,
        private val startFailure: Throwable? = null
    ) : PcmRecorder {
        val calls = mutableListOf<String>()

        override fun start() {
            calls += "start"
            startFailure?.let { throw it }
        }

        override fun read(buffer: ShortArray): Int {
            val n = if (reads.isEmpty()) buffer.size else reads.removeAt(0)
            if (n > 0) buffer.fill(16384, 0, n)
            return n
        }

        override fun stop() {
            calls += "stop"
        }

        override fun release() {
            calls += "release"
        }
    }

    private fun TestScope.source(factory: PcmRecorderFactory) =
        AudioRecordSource(factory, StandardTestDispatcher(testScheduler))

    @Test
    fun `emite chunks normalizados y libera una sola vez al cancelar`() = runTest {
        val recorder = FakeRecorder(mutableListOf())
        var requested = 0
        val recorderFor: (Int) -> PcmRecorder = {
            requested = it
            recorder
        }
        val src = source(recorderFor)
        val items = src.frames(4).take(2).toList()
        advanceUntilIdle()
        assertEquals(4, items[0].size)
        assertEquals(0.5f, items[0][0], 0f)
        assertEquals(4, requested)
        assertEquals(listOf("start", "stop", "release"), recorder.calls)
    }

    @Test
    fun `un error de lectura ocupado falla y libera`() = runTest {
        val recorder = FakeRecorder(mutableListOf(2, -6))
        source { recorder }.frames(4).test {
            assertEquals(2, awaitItem().size)
            assertSame(TunerFailure.MicBusy, awaitError())
        }
        assertEquals(listOf("start", "stop", "release"), recorder.calls)
    }

    @Test
    fun `fallo al crear el grabador se mapea y no hay nada que liberar`() = runTest {
        source { throw TunerFailure.MicUnavailable }.frames(4).test {
            assertSame(TunerFailure.MicUnavailable, awaitError())
        }
        source { throw SecurityException("sin permiso") }.frames(4).test {
            assertSame(TunerFailure.MicPermissionDenied, awaitError())
        }
    }

    @Test
    fun `fallo al empezar mapea la excepcion y libera`() = runTest {
        val recorder = FakeRecorder(mutableListOf(), startFailure = TunerFailure.MicBusy)
        source { recorder }.frames(4).test {
            assertSame(TunerFailure.MicBusy, awaitError())
        }
        assertEquals(listOf("start", "stop", "release"), recorder.calls)
    }

    @Test
    fun `una lectura de cero muestras no emite`() = runTest {
        val recorder = FakeRecorder(mutableListOf(0, 3))
        assertEquals(3, source { recorder }.frames(4).first().size)
    }
}
