package com.violinstudio.domain.feature.recording.model

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contrato de subida (B4b W3): ruta, tipo de contenido y metadatos que `storage.rules` y `finalizeRecording` aceptan.
 * Los tests leen `storage.rules` y `functions/src/config/recordings.ts` para que un cambio de nombre rompa aqui.
 */
class RecordingUploadContractTest {
    private val rules = File("../storage.rules").readText()
    private val take = RecordedTake(
        id = "3f2a-9b",
        uid = "uidAbc123",
        path = "/x/3f2a-9b.m4a",
        durationMs = 45_250,
        sizeBytes = 500_000,
        consentEpoch = 12
    )

    private fun epochOf(epoch: Long) = RecordingUploadContract.customMetadata(take.copy(consentEpoch = epoch))

    private fun whitelist(): List<String> {
        val raw = Regex("""m\.keys\(\)\.hasOnly\(\[([^\]]*)\]\)""").find(rules)?.groupValues?.get(1)
        assertNotNull(raw, "validMeta.hasOnly no encontrado en storage.rules")
        return Regex("""'([A-Za-z]+)'""").findAll(raw!!).map { it.groupValues[1] }.toList()
    }

    @Test
    fun `la clave del sello de epoca es recordingConsentEpoch y esta en reglas`() {
        assertEquals("recordingConsentEpoch", RecordingUploadContract.EPOCH_KEY)
        assertTrue(RecordingUploadContract.EPOCH_KEY in whitelist())
        assertTrue("m.recordingConsentEpoch" in rules)
    }

    @Test
    fun `la subida siempre lleva el sello de epoca como decimal canonico`() {
        assertEquals("12", epochOf(12)["recordingConsentEpoch"])
        assertEquals("0", epochOf(0)["recordingConsentEpoch"])
        val canonical = Regex(Regex("""recordingConsentEpoch\.matches\('([^']+)'\)""").find(rules)!!.groupValues[1])
        listOf(0L, 7L, 999_999_999L).forEach {
            assertTrue(canonical.matches(epochOf(it)["recordingConsentEpoch"]!!))
        }
    }

    @Test
    fun `una epoca fuera de la forma canonica de las reglas no se sube`() {
        listOf(-1L, 1_000_000_000L).forEach {
            assertThrows(IllegalArgumentException::class.java) { epochOf(it) }
        }
    }

    @Test
    fun `todas las claves de metadatos estan en la lista blanca de reglas y las obligatorias presentes`() {
        val keys = RecordingUploadContract.customMetadata(take).keys
        assertTrue(whitelist().containsAll(keys), "claves fuera de la lista blanca: ${keys - whitelist().toSet()}")
        assertEquals(setOf("kind", "durationMs", "recordingConsentEpoch"), keys)
        assertEquals("audio", RecordingUploadContract.customMetadata(take)["kind"])
        assertEquals("45250", RecordingUploadContract.customMetadata(take)["durationMs"])
        assertTrue(Regex("""durationMs\.matches\('\^\[0-9]\{1,6}\$'\)""").containsMatchIn(rules))
    }

    @Test
    fun `una duracion que no cabe en 6 digitos no se sube`() {
        assertThrows(IllegalArgumentException::class.java) {
            RecordingUploadContract.customMetadata(take.copy(durationMs = 1_000_000))
        }
    }

    @Test
    fun `ruta y tipo de contenido coinciden con reglas y finalize`() {
        assertEquals("users/uidAbc123/recordings/3f2a-9b.m4a", RecordingUploadContract.objectPath(take))
        assertEquals("audio/mp4", RecordingUploadContract.CONTENT_TYPE)
        assertTrue("r.contentType == '${RecordingUploadContract.CONTENT_TYPE}'" in rules)
        assertTrue("match /users/{uid}/${RecordingUploadContract.RECORDINGS_DIR}/{file}" in rules)
        val server = File("../functions/src/config/recordings.ts").readText()
        assertTrue("""RECORDINGS_DIR = "${RecordingUploadContract.RECORDINGS_DIR}"""" in server)
    }
}
