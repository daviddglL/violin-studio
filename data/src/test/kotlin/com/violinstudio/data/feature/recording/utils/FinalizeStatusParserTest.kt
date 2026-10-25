package com.violinstudio.data.feature.recording.utils

import com.violinstudio.domain.feature.recording.model.FailReason
import com.violinstudio.domain.feature.recording.model.FinalizeStatus
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FinalizeStatusParserTest {
    private fun rejected(reason: String?) =
        FinalizeStatusParser.parse(true, mapOf("status" to "rejected", "rejectReason" to reason))

    @Test
    fun `sin doc o con un estado desconocido la toma sigue esperando`() {
        assertEquals(FinalizeStatus.Pending, FinalizeStatusParser.parse(false, null))
        assertEquals(FinalizeStatus.Pending, FinalizeStatusParser.parse(true, null))
        assertEquals(FinalizeStatus.Pending, FinalizeStatusParser.parse(true, mapOf("status" to "processing")))
    }

    @Test
    fun `ready y los motivos de rechazo se traducen`() {
        assertEquals(FinalizeStatus.Ready, FinalizeStatusParser.parse(true, mapOf("status" to "ready")))
        assertEquals(FinalizeStatus.ConsentRejected, rejected("consent"))
        assertEquals(FinalizeStatus.Rejected(FailReason.QUOTA), rejected("quota"))
        assertEquals(FinalizeStatus.Rejected(FailReason.SIZE), rejected("size"))
        assertEquals(FinalizeStatus.Rejected(FailReason.SIZE), rejected("duration"))
        assertEquals(FinalizeStatus.Rejected(FailReason.PERMISSION), rejected("owner"))
        assertEquals(FinalizeStatus.Rejected(FailReason.PERMISSION), rejected("sharedWith"))
        assertEquals(FinalizeStatus.Rejected(FailReason.UNKNOWN), rejected("container"))
        assertEquals(FinalizeStatus.Rejected(FailReason.UNKNOWN), rejected(null))
    }

    @Test
    fun `todos los motivos del servidor siguen existiendo`() {
        val validate = File("../backend/functions/src/recordings/validate.ts").readText()
        val finalize = File("../backend/functions/src/recordings/finalize.ts").readText()
        listOf("consent", "owner", "disabled", "size", "duration", "assignment", "sharedWith").forEach {
            assertTrue("\"$it\"" in validate, "motivo $it no encontrado en validate.ts")
        }
        assertTrue("""RejectReason | "quota" | "internal"""" in finalize)
        assertTrue("""status: "rejected"""" in finalize && "rejectReason: reason" in finalize)
    }
}
