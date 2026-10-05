package com.violinstudio.data.commons.di

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
    fun `el dispatcher por defecto no es el de audio`() {
        val default: CoroutineDispatcher = AudioModule.provideDefaultDispatcher()
        assertSame(Dispatchers.Default, default)
        assertNotEquals(default, Dispatchers.IO)
    }
}

private fun threadName(): String = Thread.currentThread().name.substringBefore(" @")
