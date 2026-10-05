package com.violinstudio.ui.feature.tuner.view

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    @Test
    fun `la aguja escala con la escala minima y maxima`() {
        assertEquals(1f, needleFraction(30.0, 25))
        assertEquals(0.5f, needleFraction(100.0, 200))
        assertEquals(-1f, needleFraction(-250.0, 200))
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

    @Test
    fun `el desborde compara con los cents redondeados que se muestran`() {
        assertFalse(isOffScale(50.4, 50))
        assertNull(overflowLabel(-50.4, 50))
        assertTrue(isOffScale(50.6, 50))
    }

    @Test
    fun `una entrada no finita no rompe, guion y aguja centrada`() {
        assertEquals("—", centsLabel(Double.NaN))
        assertEquals("—", centsLabel(Double.POSITIVE_INFINITY))
        assertEquals(0f, needleFraction(Double.NaN, 50))
        assertEquals(0f, needleFraction(Double.NEGATIVE_INFINITY, 50))
        assertFalse(isOffScale(Double.NaN, 50))
    }
}
