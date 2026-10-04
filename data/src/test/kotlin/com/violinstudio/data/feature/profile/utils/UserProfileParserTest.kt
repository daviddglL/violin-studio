package com.violinstudio.data.feature.profile.utils

import com.violinstudio.data.feature.profile.utils.extensions.toDomain
import com.violinstudio.domain.feature.profile.model.ConsentStatus
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.profile.model.Role
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UserProfileParserTest {
    private val full = mapOf(
        "displayName" to "Ana",
        "instrument" to "cello",
        "locale" to "es-ES",
        "role" to "student",
        "birthDate" to "2012-05-01",
        "isMinor" to true,
        "consentStatus" to "granted",
        "policyVersion" to 2L,
        "guardian" to mapOf("emailMasked" to "t***@x.com", "sends" to listOf(1L, 2L), "requestId" to "r"),
        "deletion" to mapOf("state" to "in_progress")
    )

    private fun domain(data: Map<String, Any?>) = UserProfileParser.parse(data).toDomain("u1")

    @Test
    fun `documento completo se convierte sin perder campos`() {
        val p = domain(full)
        assertEquals("u1", p.uid)
        assertEquals("Ana", p.displayName)
        assertEquals(Instrument.CELLO, p.instrument)
        assertEquals("es-ES", p.locale)
        assertEquals(Role.STUDENT, p.role)
        assertTrue(p.isMinor)
        assertEquals(ConsentStatus.GRANTED, p.consentStatus)
        assertEquals(2, p.policyVersion)
        assertEquals("t***@x.com", p.guardian!!.emailMasked)
        assertEquals(2, p.guardian!!.sends)
        assertTrue(p.deletionInProgress)
    }

    @Test
    fun `birthDate no se expone ni en el DTO`() {
        val dto = UserProfileParser.parse(full)
        assertFalse(dto.javaClass.declaredFields.any { it.name.contains("birth", ignoreCase = true) })
        assertFalse(dto.toString().contains("2012"))
    }

    @Test
    fun `documento vacio aplica los valores fail-closed`() {
        val p = domain(emptyMap())
        assertEquals("", p.displayName)
        assertEquals(Instrument.OTHER, p.instrument)
        assertEquals(Role.INDEPENDENT, p.role)
        assertTrue(p.isMinor)
        assertEquals(ConsentStatus.PENDING, p.consentStatus)
        assertNull(p.policyVersion)
        assertNull(p.guardian)
        assertFalse(p.deletionInProgress)
    }

    @Test
    fun `tipos erroneos no revientan`() {
        val p = domain(
            mapOf(
                "displayName" to 5,
                "instrument" to listOf("x"),
                "role" to true,
                "isMinor" to "yes",
                "consentStatus" to 3,
                "policyVersion" to "2",
                "guardian" to "texto",
                "deletion" to null
            )
        )
        assertEquals("", p.displayName)
        assertTrue(p.isMinor)
        assertEquals(ConsentStatus.PENDING, p.consentStatus)
        assertNull(p.policyVersion)
        assertNull(p.guardian)
        assertFalse(p.deletionInProgress)
    }

    @Test
    fun `valores desconocidos de servidor futuro caen en los valores seguros`() {
        val p = domain(mapOf("consentStatus" to "suspended", "role" to "astronaut", "instrument" to "tuba"))
        assertEquals(ConsentStatus.PENDING, p.consentStatus)
        assertEquals(Role.INDEPENDENT, p.role)
        assertEquals(Instrument.OTHER, p.instrument)
    }

    @Test
    fun `policyVersion acepta enteros y rechaza decimales`() {
        assertEquals(3, domain(mapOf("policyVersion" to 3)).policyVersion)
        assertEquals(3, domain(mapOf("policyVersion" to 3.0)).policyVersion)
        assertNull(domain(mapOf("policyVersion" to 2.5)).policyVersion)
    }

    @Test
    fun `guardian acepta el contador como numero y requiere emailMasked`() {
        assertEquals(4, domain(mapOf("guardian" to mapOf("emailMasked" to "a***", "sends" to 4))).guardian!!.sends)
        assertNull(domain(mapOf("guardian" to mapOf("sends" to listOf(1L)))).guardian)
        assertEquals(0, domain(mapOf("guardian" to mapOf("emailMasked" to "a***"))).guardian!!.sends)
    }

    @Test
    fun `deletion en cualquier forma no nula marca borrado en curso`() {
        assertTrue(domain(mapOf("deletion" to mapOf("state" to "in_progress"))).deletionInProgress)
    }
}
