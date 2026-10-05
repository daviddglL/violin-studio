package com.violinstudio.domain.feature.tuner.model

import com.violinstudio.domain.feature.tuner.failure.TunerFailure
import com.violinstudio.domain.feature.tuner.failure.TunerField
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TunerReadingTest {
    private fun config(label: String) = TuningConfiguration("id", label, ReferencePitch.DEFAULT, MaxCents.DEFAULT)

    @Test
    fun `label de 1 a 30 caracteres es valido`() {
        assertEquals("a", config("a").label)
        assertEquals(30, config("x".repeat(30)).label.length)
    }

    @Test
    fun `label vacio, en blanco o de 31 caracteres se rechaza`() {
        for (bad in listOf("", "   ", "x".repeat(31))) {
            assertEquals(TunerFailure.InvalidConfig(TunerField.LABEL), assertThrows<TunerFailure> { config(bad) })
        }
    }

    @Test
    fun `TunerConfig por defecto`() {
        val c = TunerConfig()
        assertEquals(ReferencePitch.DEFAULT, c.referencePitch)
        assertEquals(MaxCents.DEFAULT, c.maxCents)
        assertEquals(emptyList<TuningConfiguration>(), c.presets)
        assertNull(c.selectedPresetId)
    }

    @Test
    fun `hasta 20 presets y 21 se rechaza`() {
        val presets = { n: Int -> List(n) { config("p$it").copy(id = "id$it") } }
        assertEquals(20, TunerConfig(presets = presets(20)).presets.size)
        assertSame(
            TunerFailure.PresetLimitReached,
            assertThrows<TunerFailure> { TunerConfig(presets = presets(21)) }
        )
    }

    @Test
    fun `formas de TunerReading`() {
        val target = TuningTarget.OpenString(Note(69), 2)
        val readings = listOf(
            TunerReading.Idle,
            TunerReading.NoPitch,
            TunerReading.Pitch(440.0, target, 0.0, 0.9)
        )
        assertEquals(3, readings.map { it::class }.toSet().size)
        assertEquals(target, (readings[2] as TunerReading.Pitch).target)
    }
}
