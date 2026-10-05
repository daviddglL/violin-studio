package com.violinstudio.ui.feature.tuner.view

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class TuningWheelGeometryTest {
    @Test
    fun `la aguja se ancla al borde cuando los cents superan maxCents`() {
        assertEquals(1f, needleFraction(87.0, 50))
        assertEquals(-1f, needleFraction(-87.0, 50))
        assertEquals(0.5f, needleFraction(25.0, 50))
        assertEquals(0f, needleFraction(0.0, 50))
    }

    @ParameterizedTest
    @ValueSource(ints = [25, 50, 200, 35])
    fun `los ticks son simetricos, incluyen el 0 y llegan a los extremos`(max: Int) {
        val ticks = tickValues(max)
        assertEquals(-max, ticks.first())
        assertEquals(max, ticks.last())
        assertTrue(0 in ticks)
        assertEquals(ticks, ticks.map { -it }.reversed())
        assertTrue(ticks.size in 5..21, "ticks legibles: $ticks")
    }

    @Test
    fun `la etiqueta de desborde solo aparece fuera de maxCents y lleva signo`() {
        assertNull(overflowLabel(50.0, 50))
        assertEquals("+87 ¢", overflowLabel(86.6, 50))
        assertEquals("-87 ¢", overflowLabel(-86.6, 50))
    }
}
