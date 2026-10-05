package com.violinstudio.data.commons.erasure

import kotlinx.coroutines.awaitCancellation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FirestoreCachePurgeTest {
    private val flag = FakeCachePurgeFlag()

    @Test
    fun `con el flag activo se purga una vez en el arranque y se baja el flag`() {
        flag.pending = true
        var clears = 0
        FirestoreCachePurge.runIfRequested(flag) { clears++ }
        FirestoreCachePurge.runIfRequested(flag) { clears++ }
        assertEquals(1, clears)
        assertFalse(flag.pending)
    }

    @Test
    fun `sin flag no se purga`() {
        var clears = 0
        FirestoreCachePurge.runIfRequested(flag) { clears++ }
        assertEquals(0, clears)
    }

    @Test
    fun `si la purga falla el flag se conserva y el arranque no se rompe`() {
        flag.pending = true
        FirestoreCachePurge.runIfRequested(flag) { error("en uso") }
        assertTrue(flag.pending)
    }

    @Test
    fun `una purga que no termina vence el limite y conserva el flag`() {
        flag.pending = true
        FirestoreCachePurge.runIfRequested(flag, timeoutMs = 50) { awaitCancellation() }
        assertTrue(flag.pending)
    }
}
