package com.violinstudio.domain.feature.tuner.usecase

import com.violinstudio.domain.feature.tuner.failure.TunerFailure
import com.violinstudio.domain.feature.tuner.model.Note
import com.violinstudio.domain.feature.tuner.model.ReferencePitch
import com.violinstudio.domain.testing.FakeAudioOutput
import kotlin.math.abs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayReferenceToneUseCaseTest {
    private val output = FakeAudioOutput()
    private val play = PlayReferenceToneUseCase(output)
    private val a4 = Note(69)
    private val ref = ReferencePitch.DEFAULT

    @Test
    fun `emite una sola vez cuando la salida ya suena`() = runTest {
        var emissions = 0
        val job = launch { play(a4, ref).collect { emissions++ } }
        advanceTimeBy(200)
        job.cancel()
        advanceUntilIdle()
        assertEquals(1, emissions)
    }

    @Test
    fun `cancelar cierra el tono con rampa y libera la salida una vez`() = runTest {
        val job = launch { play(a4, ref).collect { } }
        advanceTimeBy(200)
        job.cancel()
        advanceUntilIdle()
        assertEquals(1, output.releases)
        assertEquals(0f, abs(output.written.last()))
    }

    @Test
    fun `cambiar de cuerda tras cancelar mantiene una sola salida activa`() = runTest {
        for (note in listOf(a4, Note(76))) {
            val job = launch { play(note, ref).collect { } }
            advanceTimeBy(100)
            job.cancel()
            advanceUntilIdle()
        }
        assertEquals(1, output.maxActive)
        assertEquals(2, output.releases)
    }

    @Test
    fun `un fallo de la salida llega al colector`() = runTest {
        val failing = PlayReferenceToneUseCase(FakeAudioOutput(failure = TunerFailure.AudioOutputUnavailable))
        val result = runCatching { failing(a4, ref).collect { } }
        assertSame(TunerFailure.AudioOutputUnavailable, result.exceptionOrNull())
    }
}
