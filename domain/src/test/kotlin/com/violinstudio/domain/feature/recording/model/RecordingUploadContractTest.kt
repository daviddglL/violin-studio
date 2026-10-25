package com.violinstudio.domain.feature.recording.model

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contrato de subida (B4b W3): ruta, tipo de contenido y metadatos que `storage.rules` y `finalizeRecording` aceptan.
 * Los tests leen `storage.rules` y `functions/src/config/recordings.ts` para que un cambio de nombre rompa aqui.
 */
class RecordingUploadContractTest {
    private val rules = File("../backend/storage.rules").readText()

    // Sin espacios ni saltos de linea: los patrones no dependen de como se envuelvan las reglas (ninguno de los
    // literales comparados lleva espacios).
    private val compact = rules.filterNot { it.isWhitespace() }
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
        val raw = Regex("""m\.keys\(\)\.hasOnly\(\[([^\]]*)\]\)""").find(compact)?.groupValues?.get(1)
        assertNotNull(raw, "validMeta.hasOnly no encontrado en storage.rules")
        return Regex("""'([A-Za-z]+)'""").findAll(raw!!).map { it.groupValues[1] }.toList()
    }

    @Test
    fun `la clave del sello de epoca es recordingConsentEpoch y esta en reglas`() {
        assertEquals("recordingConsentEpoch", RecordingUploadContract.EPOCH_KEY)
        assertTrue(RecordingUploadContract.EPOCH_KEY in whitelist())
        assertTrue("m.recordingConsentEpoch" in compact)
    }

    private fun epochPattern(): String {
        val match = Regex("""recordingConsentEpoch\.matches\('([^']+)'\)""").find(compact)
        return match!!.groupValues[1]
    }

    @Test
    fun `la subida siempre lleva el sello de epoca como decimal canonico`() {
        assertEquals("12", epochOf(12)["recordingConsentEpoch"])
        assertEquals("0", epochOf(0)["recordingConsentEpoch"])
        val canonical = Regex(epochPattern())
        listOf(0L, 7L, 999_999_999L).forEach {
            assertTrue(canonical.matches(epochOf(it)["recordingConsentEpoch"]!!))
        }
    }

    @Test
    fun `el limite de epoca del contrato se deriva del cuantificador de las reglas`() {
        // '^(0|[1-9][0-9]{0,8})$' -> 1 + 8 digitos como maximo.
        val extraDigits = Regex("""\{0,(\d+)}""").find(epochPattern())!!.groupValues[1].toInt()
        val max = Math.pow(10.0, (extraDigits + 1).toDouble()).toLong() - 1
        assertTrue(Regex(epochPattern()).matches(max.toString()))
        assertFalse(Regex(epochPattern()).matches((max + 1).toString()))
        assertEquals(max.toString(), epochOf(max)["recordingConsentEpoch"])
        assertThrows(IllegalArgumentException::class.java) { epochOf(max + 1) }
        assertThrows(IllegalArgumentException::class.java) { epochOf(-1) }
    }

    @Test
    fun `el limite de duracion del contrato se deriva del cuantificador de las reglas`() {
        val pattern = Regex("""durationMs\.matches\('([^']+)'\)""").find(compact)!!.groupValues[1]
        val digits = Regex("""\{1,(\d+)}""").find(pattern)!!.groupValues[1].toInt()
        val max = Math.pow(10.0, digits.toDouble()).toLong() - 1
        assertEquals(max.toString(), RecordingUploadContract.customMetadata(take.copy(durationMs = max))["durationMs"])
        assertThrows(IllegalArgumentException::class.java) {
            RecordingUploadContract.customMetadata(take.copy(durationMs = max + 1))
        }
    }

    @Test
    fun `todas las claves de metadatos estan en la lista blanca de reglas y las obligatorias presentes`() {
        val keys = RecordingUploadContract.customMetadata(take).keys
        assertTrue(whitelist().containsAll(keys), "claves fuera de la lista blanca: ${keys - whitelist().toSet()}")
        assertEquals(setOf("kind", "durationMs", "recordingConsentEpoch"), keys)
        assertEquals("audio", RecordingUploadContract.customMetadata(take)["kind"])
        assertEquals("45250", RecordingUploadContract.customMetadata(take)["durationMs"])
        assertTrue(Regex("""durationMs\.matches\('\^\[0-9]\{1,\d+}\$'\)""").containsMatchIn(compact))
    }

    @Test
    fun `el valor de kind es el que las reglas exigen a un audio`() {
        val kind = RecordingUploadContract.customMetadata(take)["kind"]
        // validMeta admite audio y video; validAudio (la ruta de grabaciones) exige exactamente 'audio'.
        assertTrue("m.kindin['audio','video']" in compact)
        assertTrue("r.metadata.kind=='$kind'" in compact)
        assertEquals("audio", kind)
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
        assertTrue("r.contentType=='${RecordingUploadContract.CONTENT_TYPE}'" in compact)
        assertTrue("match/users/{uid}/${RecordingUploadContract.RECORDINGS_DIR}/{file}" in compact)
        val server = File("../backend/functions/src/config/recordings.ts").readText()
        assertTrue("""RECORDINGS_DIR = "${RecordingUploadContract.RECORDINGS_DIR}"""" in server)
    }

    @Test
    fun `una demo va a demos con tarea y destinatario y una grabacion propia no lleva destinatario`() {
        val own = QueuedRecording(take, assignmentId = "as-1")
        assertEquals("users/uidAbc123/recordings/3f2a-9b.m4a", RecordingUploadContract.objectPath(own))
        assertEquals("as-1", RecordingUploadContract.customMetadata(own)["assignmentId"])
        assertFalse("sharedWith" in RecordingUploadContract.customMetadata(own))

        val demo = QueuedRecording(take, assignmentId = "as-1", sharedWith = "student9")
        assertEquals("users/uidAbc123/demos/3f2a-9b.m4a", RecordingUploadContract.objectPath(demo))
        val meta = RecordingUploadContract.customMetadata(demo)
        assertEquals("student9", meta["sharedWith"])
        assertTrue(whitelist().containsAll(meta.keys))
        assertTrue("match/users/{uid}/${RecordingUploadContract.DEMOS_DIR}/{file}" in compact)
        assertTrue("'sharedWith'inrequest.resource.metadata&&'assignmentId'inrequest.resource.metadata" in compact)
    }

    @Test
    fun `tarea y destinatario fuera del formato de las reglas no se suben`() {
        listOf(
            QueuedRecording(take, assignmentId = "con espacio"),
            QueuedRecording(take, assignmentId = "a".repeat(65)),
            QueuedRecording(take, assignmentId = "as-1", sharedWith = "no_vale"),
            QueuedRecording(take, sharedWith = "student9")
        ).forEach { assertThrows(IllegalArgumentException::class.java) { RecordingUploadContract.customMetadata(it) } }
    }

    private val video = take.copy(
        path = "/x/3f2a-9b.mp4",
        kind = MediaKind.VIDEO,
        blurPolicyVersion = FaceBlurPolicy.VERSION
    )

    @Test
    fun `un video difuminado va como mp4 video con el sello que exigen las reglas`() {
        assertEquals("users/uidAbc123/recordings/3f2a-9b.mp4", RecordingUploadContract.objectPath(video))
        assertEquals("video/mp4", RecordingUploadContract.contentType(video))
        assertEquals("audio/mp4", RecordingUploadContract.contentType(take))
        val meta = RecordingUploadContract.customMetadata(video)
        assertEquals("video", meta["kind"])
        assertEquals("true", meta["blurApplied"])
        assertEquals(FaceBlurPolicy.VERSION.toString(), meta["blurPolicyVersion"])
        assertTrue(
            whitelist().containsAll(meta.keys),
            "claves fuera de la lista blanca: ${meta.keys - whitelist().toSet()}"
        )
        assertTrue("r.contentType=='${RecordingUploadContract.CONTENT_TYPE_VIDEO}'" in compact)
        assertTrue("r.metadata.kind=='video'" in compact)
        assertTrue("r.metadata.get('blurApplied','')=='true'" in compact)
        assertTrue("r.metadata.get('blurPolicyVersion','')=='${FaceBlurPolicy.VERSION}'" in compact)
        assertTrue("(m4a|mp4)" in compact)
    }

    @Test
    fun `una demo de video va a demos como mp4`() {
        val demo = QueuedRecording(video, assignmentId = "as-1", sharedWith = "student9")
        assertEquals("users/uidAbc123/demos/3f2a-9b.mp4", RecordingUploadContract.objectPath(demo))
        assertEquals("true", RecordingUploadContract.customMetadata(demo)["blurApplied"])
    }

    @Test
    fun `un audio no lleva sello de difuminado y un video sin version no se sube`() {
        assertFalse("blurApplied" in RecordingUploadContract.customMetadata(take))
        assertThrows(IllegalArgumentException::class.java) {
            RecordingUploadContract.customMetadata(video.copy(blurPolicyVersion = null))
        }
    }
}
