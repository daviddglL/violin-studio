package com.violinstudio.domain.feature.profile.model

import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ProfileModelTest {
    private val birth = LocalDate.of(2000, 1, 1)

    @Test
    fun `Instrument y ConsentStatus se resuelven por su valor de red`() {
        assertEquals(Instrument.DOUBLE_BASS, Instrument.fromWire("double_bass"))
        assertEquals("double_bass", Instrument.DOUBLE_BASS.wire)
        assertNull(Instrument.fromWire("piano"))
        assertEquals(
            listOf("pending", "parental_pending", "granted", "revoked"),
            ConsentStatus.entries.map { it.wire }
        )
        assertEquals(ConsentStatus.REVOKED, ConsentStatus.fromWire("revoked"))
        assertNull(ConsentStatus.fromWire(null))
    }

    @Test
    fun `teacher no es asignable en el registro`() {
        assertFalse(Role.TEACHER.assignableAtRegistration)
        assertTrue(Role.INDEPENDENT.assignableAtRegistration)
        assertEquals(Role.TEACHER, Role.fromWire("teacher"))
        assertNull(Role.fromWire("admin"))
    }

    @Test
    fun `ProfileRegistration valida nombre y locale como el servidor`() {
        // Se valida el nombre recortado (como el servidor); recortar al enviar es cosa de la capa de datos.
        assertEquals("  Ana  ", ProfileRegistration(birth, "  Ana  ", Instrument.VIOLIN, "es-ES").displayName)
        assertThrows<IllegalArgumentException> { ProfileRegistration(birth, " ", Instrument.VIOLIN, "es") }
        assertThrows<IllegalArgumentException> { ProfileRegistration(birth, "x".repeat(41), Instrument.VIOLIN, "es") }
        assertThrows<IllegalArgumentException> { ProfileRegistration(birth, "Ana", Instrument.VIOLIN, "ES") }
    }

    @Test
    fun `EditableProfile aplica las mismas reglas y cuenta puntos de codigo`() {
        val violin = "🎻"
        assertEquals(80, EditableProfile(violin.repeat(40), Instrument.CELLO, "en").displayName.length)
        assertThrows<IllegalArgumentException> { EditableProfile(violin.repeat(41), Instrument.CELLO, "en") }
        assertThrows<IllegalArgumentException> { EditableProfile("Ana", Instrument.CELLO, "english") }
    }

    @Test
    fun `UserProfile indica si el consentimiento cubre una version`() {
        val granted = profile(ConsentStatus.GRANTED, policyVersion = 2)
        assertTrue(granted.isConsentCurrent(2))
        assertTrue(granted.isConsentCurrent(1))
        assertFalse(granted.isConsentCurrent(3))
        assertFalse(profile(ConsentStatus.REVOKED, policyVersion = 2).isConsentCurrent(1))
        assertFalse(profile(ConsentStatus.GRANTED, policyVersion = null).isConsentCurrent(1))
    }

    private fun profile(status: ConsentStatus, policyVersion: Int?) = UserProfile(
        uid = "u1",
        displayName = "Ana",
        instrument = Instrument.VIOLIN,
        locale = "es",
        role = Role.INDEPENDENT,
        isMinor = false,
        consentStatus = status,
        policyVersion = policyVersion,
        guardian = null,
        deletionInProgress = false
    )
}
