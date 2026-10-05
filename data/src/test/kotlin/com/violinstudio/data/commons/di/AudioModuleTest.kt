package com.violinstudio.data.commons.di

import com.violinstudio.domain.feature.metronome.model.Tempo
import com.violinstudio.domain.feature.metronome.model.TimeSignature
import com.violinstudio.domain.feature.tuner.audio.AudioOutput
import com.violinstudio.domain.feature.tuner.audio.PcmGenerator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class AudioModuleTest {
    @Test
    fun `el dispatcher de entrada es un unico hilo dedicado llamado audio-in`() {
        val dispatcher = AudioModule.provideAudioInputDispatcher()
        val names = runBlocking { List(3) { withContext(dispatcher) { threadName() } } }
        assertEquals(listOf(AudioModule.AUDIO_INPUT_THREAD), names.distinct())
        (dispatcher as AutoCloseable).close()
    }

    @Test
    fun `el dispatcher de salida es un unico hilo dedicado llamado audio-out`() {
        val dispatcher = AudioModule.provideAudioOutputDispatcher()
        val names = runBlocking { List(3) { withContext(dispatcher) { threadName() } } }
        assertEquals(listOf(AudioModule.AUDIO_OUTPUT_THREAD), names.distinct())
        (dispatcher as AutoCloseable).close()
    }

    @Test
    fun `el dispatcher por defecto no es el de audio`() {
        val default: CoroutineDispatcher = AudioModule.provideDefaultDispatcher()
        assertSame(Dispatchers.Default, default)
        assertNotEquals(default, Dispatchers.IO)
    }

    @Test
    fun `el caso de uso del metronomo se construye sobre la salida compartida`() {
        val run = AudioModule.provideRunMetronomeUseCase(object : AudioOutput {
            override fun play(generator: PcmGenerator) = emptyFlow<Long>()
        })
        val session = run(Tempo(90), TimeSignature.TWO_FOUR)
        assertEquals(90, session.tempo.bpm)
    }
}

private fun threadName(): String = Thread.currentThread().name.substringBefore(" @")
