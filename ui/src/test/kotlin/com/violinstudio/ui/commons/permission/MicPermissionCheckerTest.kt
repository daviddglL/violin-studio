package com.violinstudio.ui.commons.permission

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MicPermissionCheckerTest {
    @Test
    fun `snapshot refleja concedido y rationale del checker`() {
        val checker = FakeMicPermissionChecker(granted = false, rationale = true)
        assertEquals(MicPermissionSnapshot(granted = false, rationale = true), checker.snapshot())
        checker.granted = true
        checker.rationale = false
        assertEquals(MicPermissionSnapshot(granted = true, rationale = false), checker.snapshot())
    }
}
