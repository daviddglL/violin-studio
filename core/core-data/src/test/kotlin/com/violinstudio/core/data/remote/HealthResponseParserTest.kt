package com.violinstudio.core.data.remote

import com.violinstudio.core.model.HealthInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HealthResponseParserTest {
    @Test
    fun `parsea un objeto con status y version`() {
        assertEquals(
            HealthInfo("ok", "0.1.0"),
            HealthResponseParser.parse(mapOf("status" to "ok", "version" to "0.1.0"))
        )
    }

    @Test
    fun `ignora campos extra`() {
        assertEquals(
            HealthInfo("ok", "0.1.0"),
            HealthResponseParser.parse(mapOf("status" to "ok", "version" to "0.1.0", "region" to "eu"))
        )
    }

    @Test
    fun `falla con MalformedResponseException si no es un objeto`() {
        assertThrows<MalformedResponseException> { HealthResponseParser.parse(null) }
        assertThrows<MalformedResponseException> { HealthResponseParser.parse("ok") }
    }

    @Test
    fun `falla si falta un campo o no es texto`() {
        assertThrows<MalformedResponseException> { HealthResponseParser.parse(mapOf("status" to "ok")) }
        assertThrows<MalformedResponseException> { HealthResponseParser.parse(mapOf("version" to "0.1.0")) }
        assertThrows<MalformedResponseException> {
            HealthResponseParser.parse(mapOf("status" to "ok", "version" to 1))
        }
    }
}
