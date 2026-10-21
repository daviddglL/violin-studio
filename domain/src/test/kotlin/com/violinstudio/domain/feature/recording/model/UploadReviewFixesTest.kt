package com.violinstudio.domain.feature.recording.model

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Correcciones de la revision de B7b: sin sesion el fallo es transitorio y `assignmentId` viaja si es valido. */
class UploadReviewFixesTest {
    private val take = RecordedTake("3f2a-9b", "uidAbc123", "/x/a.m4a", 45_250, 500_000, 12)
    private val compact = File("../storage.rules").readText().filterNot { it.isWhitespace() }

    @Test
    fun `un fallo por falta de sesion reintenta y no es permanente`() {
        assertEquals(UploadDecision.Retry, UploadFailureClassifier.classify(UploadError.Unauthenticated))
    }

    @Test
    fun `assignmentId valido se envia con la clave y el patron de las reglas`() {
        assertEquals("assignmentId", RecordingUploadContract.ASSIGNMENT_KEY)
        val whitelist = Regex("""m\.keys\(\)\.hasOnly\(\[([^\]]*)\]\)""").find(compact)!!.groupValues[1]
        assertTrue("'assignmentId'" in whitelist)
        val pattern = Regex("""assignmentId\.matches\('([^']+)'\)""").find(compact)?.groupValues?.get(1)
        assertNotNull(pattern)
        val meta = RecordingUploadContract.customMetadata(take, assignmentId = "as-1B")
        assertEquals("as-1B", meta["assignmentId"])
        assertTrue(Regex(pattern!!).matches(meta["assignmentId"]!!))
        assertEquals(setOf("kind", "durationMs", "recordingConsentEpoch", "assignmentId"), meta.keys)
    }

    @Test
    fun `sin assignmentId o con uno que las reglas rechazarian no se envia la clave`() {
        assertFalse("assignmentId" in RecordingUploadContract.customMetadata(take))
        listOf("", "a_b", "a/b", "a".repeat(65), "tarea 1").forEach {
            assertFalse("assignmentId" in RecordingUploadContract.customMetadata(take, it), it)
        }
        assertTrue("assignmentId" in RecordingUploadContract.customMetadata(take, "a".repeat(64)))
    }
}
