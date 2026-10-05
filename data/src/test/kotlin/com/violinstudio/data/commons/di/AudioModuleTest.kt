package com.violinstudio.data.commons.di

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun `los hilos de audio son daemon para no retener el JVM si el driver se bloquea`() {
        listOf(AudioModule.provideAudioInputDispatcher(), AudioModule.provideAudioOutputDispatcher()).forEach {
            val daemon = runBlocking { withContext(it) { Thread.currentThread().isDaemon } }
            (it as AutoCloseable).close()
            assertTrue(daemon)
        }
    }

    @Test
    fun `el dispatcher por defecto no es el de audio`() {
        val default: CoroutineDispatcher = AudioModule.provideDefaultDispatcher()
        assertSame(Dispatchers.Default, default)
        assertNotEquals(default, Dispatchers.IO)
    }
}

private fun threadName(): String = Thread.currentThread().name.substringBefore(" @")
