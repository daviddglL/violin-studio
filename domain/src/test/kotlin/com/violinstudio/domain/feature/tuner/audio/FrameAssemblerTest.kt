package com.violinstudio.domain.feature.tuner.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FrameAssemblerTest {
    private fun FrameAssembler.drain(chunk: FloatArray): List<List<Float>> {
        offer(chunk)
        val frames = mutableListOf<List<Float>>()
        while (true) frames += (poll() ?: break).toList()
        return frames
    }

    private fun ramp(from: Int, n: Int) = FloatArray(n) { (from + it).toFloat() }

    @Test
    fun `frames solapados N 4096 hop 2048`() {
        val asm = FrameAssembler(4096, 2048)
        assertEquals(0, asm.drain(ramp(0, 2048)).size)
        val first = asm.drain(ramp(2048, 2048))
        assertEquals(listOf(ramp(0, 4096).toList()), first)
        val second = asm.drain(ramp(4096, 2048))
        assertEquals(listOf(ramp(2048, 4096).toList()), second)
    }

    @Test
    fun `chunk incompleto se retiene hasta completar`() {
        val asm = FrameAssembler(8, 4)
        assertEquals(0, asm.drain(ramp(0, 3)).size)
        assertEquals(0, asm.drain(ramp(3, 4)).size)
        assertEquals(listOf(ramp(0, 8).toList()), asm.drain(ramp(7, 1)))
    }

    @Test
    fun `un chunk grande produce varios frames`() {
        val asm = FrameAssembler(8, 4)
        assertEquals(listOf(ramp(0, 8).toList(), ramp(4, 8).toList(), ramp(8, 8).toList()), asm.drain(ramp(0, 16)))
    }

    @Test
    fun `reutiliza el mismo buffer`() {
        val asm = FrameAssembler(8, 4)
        asm.offer(ramp(0, 16))
        val a = asm.poll()
        val b = asm.poll()
        assertSame(a, b)
    }

    @Test
    fun `rechaza parametros invalidos y offer con pendiente`() {
        assertThrows(IllegalArgumentException::class.java) { FrameAssembler(8, 0) }
        assertThrows(IllegalArgumentException::class.java) { FrameAssembler(8, 9) }
        val asm = FrameAssembler(8, 4)
        asm.offer(ramp(0, 16))
        assertThrows(IllegalStateException::class.java) { asm.offer(ramp(0, 1)) }
    }
}
