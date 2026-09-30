package com.violinstudio.domain.feature.health.model

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HealthInfoTest {
    @Test
    fun `isOk es true solo cuando status es ok`() {
        assertTrue(HealthInfo(status = "ok", version = "1.0.0").isOk)
        assertFalse(HealthInfo(status = "degraded", version = "1.0.0").isOk)
        assertFalse(HealthInfo(status = "OK", version = "1.0.0").isOk)
    }
}
