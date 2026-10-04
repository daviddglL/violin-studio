package com.violinstudio.domain.feature.profile.model

import com.violinstudio.domain.feature.profile.failure.ProfileFailure
import com.violinstudio.domain.feature.profile.failure.ProfileField
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProfileModelTest {
    private val birth = LocalDate.of(2000, 1, 1)
    private val violin = chars(0x1F3BB)

    private fun chars(vararg codePoints: Int) = String(codePoints, 0, codePoints.size)

    private fun registration(name: String = "Ana", locale: String = "es") =
        ProfileRegistration.create(birth, name, Instrument.VIOLIN, locale)

    private fun editable(name: String = "Ana", locale: String = "es") =
        EditableProfile.create(name, Instrument.CELLO, locale)

    private fun fieldOf(result: Result<*>): ProfileField? =
        (result.exceptionOrNull() as ProfileFailure.InvalidInput).field

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
    fun `un estado de consentimiento desconocido o ausente cuenta como pending y un isMinor ausente como menor`() {
        assertEquals(ConsentStatus.PENDING, ConsentStatus.fromWireOrPending("future_state"))
        assertEquals(ConsentStatus.PENDING, ConsentStatus.fromWireOrPending(null))
        assertEquals(ConsentStatus.GRANTED, ConsentStatus.fromWireOrPending("granted"))
        assertTrue(UserProfile.minorOrTrue(null))
        assertTrue(UserProfile.minorOrTrue(true))
        assertFalse(UserProfile.minorOrTrue(false))
    }

    @Test
    fun `teacher no es asignable en el registro`() {
        assertFalse(Role.TEACHER.assignableAtRegistration)
        assertTrue(Role.INDEPENDENT.assignableAtRegistration)
        assertEquals(Role.TEACHER, Role.fromWire("teacher"))
        assertNull(Role.fromWire("admin"))
    }

    @Test
    fun `el registro guarda el nombre recortado`() {
        assertEquals("Ana", registration("  Ana \n").getOrThrow().displayName)
        assertEquals("Ana", editable(" Ana ").getOrThrow().displayName)
    }

    @Test
    fun `el recorte coincide con el trim de JavaScript`() {
        // NBSP, U+FEFF, U+2028 y espacio ideografico se recortan; U+001F (que Kotlin trata como espacio) no.
        assertEquals("Ana", registration(chars(0xA0, 0xFEFF) + "Ana" + chars(0x2028, 0x3000)).getOrThrow().displayName)
        assertEquals(ProfileField.DISPLAY_NAME, fieldOf(registration("\u001FAna")))
    }

    @Test
    fun `nombre vacio o solo espacios es invalido`() {
        assertEquals(ProfileField.DISPLAY_NAME, fieldOf(registration("")))
        assertEquals(ProfileField.DISPLAY_NAME, fieldOf(registration("   ")))
        assertEquals(ProfileField.DISPLAY_NAME, fieldOf(editable(" \t ")))
    }

    @Test
    fun `lista blanca Unicode como el servidor y las reglas`() {
        assertTrue(registration("Zo" + chars(0xEB) + " Mu" + chars(0xF1) + "oz-O'Brien 2").isSuccess)
        assertTrue(registration("e" + chars(0x301) + " $violin").isSuccess)
        listOf(
            "A" + chars(0x0A) + "B",
            "A" + chars(0x09) + "B",
            "A" + chars(0x200B) + "B",
            "A" + chars(0xA0) + "B",
            "A" + chars(0xFEFF) + "B",
            "A" + chars(0) + "B",
            chars(0x1F) + "Ana"
        ).forEach {
            assertEquals(ProfileField.DISPLAY_NAME, fieldOf(registration(it)), "registro $it")
            assertEquals(ProfileField.DISPLAY_NAME, fieldOf(editable(it)), "edicion $it")
        }
    }

    @Test
    fun `ProfileRegistration admite 40 puntos de codigo (la regla del servidor) y rechaza 41`() {
        assertEquals(80, registration(violin.repeat(40)).getOrThrow().displayName.length)
        assertEquals(ProfileField.DISPLAY_NAME, fieldOf(registration(violin.repeat(41))))
    }

    @Test
    fun `EditableProfile limita a 40 unidades UTF-16 como Firestore, 20 emojis si y 21 no`() {
        assertTrue(editable(violin.repeat(20)).isSuccess)
        assertEquals(ProfileField.DISPLAY_NAME, fieldOf(editable(violin.repeat(21))))
        assertTrue(editable("a".repeat(40)).isSuccess)
        assertEquals(ProfileField.DISPLAY_NAME, fieldOf(editable("a".repeat(41))))
    }

    @Test
    fun `locale igual que la regex del servidor`() {
        listOf("es", "es-ES", "en", "pt-BR").forEach {
            assertTrue(registration(locale = it).isSuccess, it)
            assertTrue(editable(locale = it).isSuccess, it)
        }
        listOf("es-es", "EN", "es_ES", "english", "", "e", "es-ESP").forEach {
            assertEquals(ProfileField.LOCALE, fieldOf(registration(locale = it)), it)
            assertEquals(ProfileField.LOCALE, fieldOf(editable(locale = it)), it)
        }
    }

    @Test
    fun `toString no filtra datos personales`() {
        val text = registration("Ana Secreta").getOrThrow().toString() + editable("Ana Secreta").getOrThrow()
        assertFalse(text.contains("Ana Secreta"))
        assertFalse(text.contains("2000"))
        val profile = profile(ConsentStatus.GRANTED, 2)
        assertFalse(profile.copy(displayName = "Ana Secreta").toString().contains("Ana Secreta"))
        assertFalse(GuardianSummary("p***@g***.com", 1).toString().contains("p***"))
    }

    @Test
    fun `UserProfile indica si el consentimiento corresponde exactamente a la version vigente`() {
        assertTrue(profile(ConsentStatus.GRANTED, 2).isConsentCurrent(2))
        assertFalse(profile(ConsentStatus.GRANTED, 2).isConsentCurrent(1))
        assertFalse(profile(ConsentStatus.GRANTED, 2).isConsentCurrent(3))
        assertFalse(profile(ConsentStatus.REVOKED, 2).isConsentCurrent(2))
        assertFalse(profile(ConsentStatus.GRANTED, null).isConsentCurrent(1))
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
