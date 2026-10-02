package com.violinstudio.domain.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RetryBackoffTest {
    @Test
    fun `por defecto 1s 2s 4s y tope de 30s`() {
        val backoff = RetryBackoff()
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (0..6).map(backoff::delayFor))
    }

    @Test
    fun `es configurable y no desborda con intentos enormes`() {
        val backoff = RetryBackoff(initialMillis = 10, maxMillis = 50)
        assertEquals(listOf(10L, 20L, 40L, 50L), (0..3).map(backoff::delayFor))
        assertEquals(50L, backoff.delayFor(10_000))
    }
}
