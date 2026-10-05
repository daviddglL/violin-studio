package com.violinstudio.domain.feature.metronome

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TapTempoCalculatorTest {
    private class MutableClock(var millis: Long = 1_000_000) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(millis)
    }

    private val clock = MutableClock()
    private val calculator = TapTempoCalculator(clock)

    /** Toca y deja pasar [after] ms. */
    private fun tap(after: Long) = calculator.tap().also { clock.millis += after }

    @Test
    fun `toques cada 500 ms dan 120 BPM`() {
        assertEquals(120, (1..4).map { tap(500) }.last()?.bpm)
    }

    @Test
    fun `un solo toque no da BPM`() {
        assertNull(calculator.tap())
    }

    @Test
    fun `una pausa de 2,5 s reinicia y el segundo toque cuenta como primero`() {
        repeat(3) { tap(500) }
        clock.millis += 2_000
        assertNull(calculator.tap())
        clock.millis += 600
        assertEquals(100, calculator.tap()?.bpm)
    }

    @Test
    fun `toques cada 100 ms se acotan a 250`() {
        assertEquals(250, (1..3).map { tap(100) }.last()?.bpm)
    }

    @Test
    fun `promedia solo los ultimos 4 intervalos`() {
        listOf(1_000L, 1_000L, 500L, 500L, 500L, 500L).forEach { tap(it) }
        assertEquals(120, calculator.tap()?.bpm)
    }
}
