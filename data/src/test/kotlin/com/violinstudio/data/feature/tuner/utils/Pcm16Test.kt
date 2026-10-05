package com.violinstudio.data.feature.tuner.utils

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Pcm16Test {
    @Test
    fun `divide cada muestra entre 32768`() {
        val out = Pcm16.toFloats(shortArrayOf(0, 16384, -16384))
        assertArrayEquals(floatArrayOf(0f, 0.5f, -0.5f), out, 0f)
    }

    @Test
    fun `los extremos caen en el intervalo -1 a 1 sin incluir 1`() {
        val out = Pcm16.toFloats(shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE))
        assertEquals(-1f, out[0], 0f)
        assertTrue(out[1] < 1f)
    }

    @Test
    fun `un array vacio da un array vacio`() {
        assertEquals(0, Pcm16.toFloats(ShortArray(0)).size)
    }

    @Test
    fun `solo convierte las primeras count muestras`() {
        val out = Pcm16.toFloats(shortArrayOf(100, 200, 300), count = 2)
        assertEquals(2, out.size)
    }
}
