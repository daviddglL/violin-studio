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
        FirestoreCachePurge.runIfRequested(flag, isSignedIn = { false }) { clears++ }
        FirestoreCachePurge.runIfRequested(flag, isSignedIn = { false }) { clears++ }
        assertEquals(1, clears)
        assertFalse(flag.pending)
    }

    @Test
    fun `sin flag no se purga`() {
        var clears = 0
        FirestoreCachePurge.runIfRequested(flag, isSignedIn = { false }) { clears++ }
        assertEquals(0, clears)
    }

    @Test
    fun `si la purga falla el flag se conserva y el arranque no se rompe`() {
        flag.pending = true
        FirestoreCachePurge.runIfRequested(flag, isSignedIn = { false }) { error("en uso") }
        assertTrue(flag.pending)
    }

    @Test
    fun `una purga que no termina vence el limite y conserva el flag`() {
        flag.pending = true
        FirestoreCachePurge.runIfRequested(flag, isSignedIn = { false }, timeoutMs = 50) { awaitCancellation() }
        assertTrue(flag.pending)
    }

    @Test
    fun `con sesion iniciada no se purga y el flag se conserva (issue 47)`() {
        flag.pending = true
        var clears = 0
        FirestoreCachePurge.runIfRequested(flag, isSignedIn = { true }) { clears++ }
        assertEquals(0, clears)
        assertTrue(flag.pending)
    }

    @Test
    fun `tras conservar el flag con sesion se purga en un arranque posterior sin sesion`() {
        flag.pending = true
        var clears = 0
        FirestoreCachePurge.runIfRequested(flag, isSignedIn = { true }) { clears++ }
        FirestoreCachePurge.runIfRequested(flag, isSignedIn = { false }) { clears++ }
        assertEquals(1, clears)
        assertFalse(flag.pending)
    }
}
