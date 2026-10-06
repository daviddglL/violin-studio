package com.violinstudio.data.commons.audio

import com.violinstudio.data.feature.practice.repository.FirestorePracticeLogRepository
import com.violinstudio.data.feature.tuner.repository.DataStoreTunerConfigRepository
import com.violinstudio.domain.feature.practice.repository.PracticeLogRepository
import com.violinstudio.domain.feature.tuner.repository.TunerConfigRepository
import java.lang.reflect.Method
import java.lang.reflect.Type
import java.nio.ByteBuffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** REQ-AUD-01/02: las firmas de los repositorios que persisten o transmiten no contienen tipos de audio. */
class PracticeAudioSignaturesTest {
    private val repositories = listOf(
        PracticeLogRepository::class.java,
        TunerConfigRepository::class.java,
        FirestorePracticeLogRepository::class.java,
        DataStoreTunerConfigRepository::class.java
    )

    @Test
    fun `los repositorios no exponen tipos de audio`() {
        repositories.forEach { type ->
            assertTrue(type.declaredMethods.isNotEmpty(), "sin metodos: ${type.simpleName}")
            val found = audioTypesIn(type)
            assertEquals(emptyList<String>(), found, "${type.simpleName} expone tipos de audio: $found")
        }
    }

    @Test
    fun `el escaner detecta firmas con audio`() {
        val bad = audioTypesIn(Bad::class.java).map { it.substringBefore("(") }
        assertTrue(bad.containsAll(listOf("write", "samples", "audio")), "$bad")
        assertTrue(audioTypesIn(BadField::class.java).any { it.startsWith("buffer(") })
    }

    private fun audioTypesIn(type: Class<*>): List<String> {
        val methods = type.declaredMethods.filterNot { it.isSynthetic }.flatMap { m -> m.audioTypes() }
        val fields = type.declaredFields.filter { isAudio(it.genericType) }
            .map { "${it.name}(${it.genericType.typeName})" }
        return (methods + fields).distinct()
    }

    private fun Method.audioTypes(): List<String> =
        (genericParameterTypes.toList() + genericReturnType).filter(::isAudio).map { "$name(${it.typeName})" }

    private fun isAudio(type: Type): Boolean {
        val text = type.typeName
        return AUDIO.any { text.contains(it) }
    }

    @Suppress("unused")
    private class Bad {
        fun write(a: FloatArray) = a
        fun samples(): ShortArray = ShortArray(0)
        fun audio(b: ByteBuffer) = b
    }

    @Suppress("unused")
    private class BadField(val buffer: ByteArray)

    private companion object {
        val AUDIO = listOf("float[]", "short[]", "byte[]", "ByteBuffer", "AudioFrame", "AudioInputSource")
    }
}
