package com.violinstudio.data.feature.recording.utils

import com.google.firebase.Timestamp
import com.violinstudio.domain.feature.recording.model.MediaKind
import com.violinstudio.domain.feature.recording.model.RecordingSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RecordingSummaryParserTest {
    @Test
    fun `un doc ready de finalize se lee entero`() {
        val data = mapOf(
            "status" to "ready",
            "createdAt" to Timestamp(1_700_000_000, 250_000_000),
            "durationSec" to 12.5,
            "objectPath" to "users/u/recordings/r1.m4a"
        )
        assertEquals(
            RecordingSummary(
                "r1",
                1_700_000_000_250,
                12_500,
                ready = true,
                isDemo = false,
                objectPath = "users/u/recordings/r1.m4a"
            ),
            RecordingSummaryParser.parse("r1", data)
        )
    }

    @Test
    fun `una demo rechazada sin fecha ni duracion legibles`() {
        val data = mapOf(
            "status" to "rejected",
            "createdAt" to "ayer",
            "durationSec" to -1,
            "sharedWith" to "s1",
            "assignmentId" to "a1"
        )
        assertEquals(
            RecordingSummary("r2", null, null, ready = false, isDemo = true, assignmentId = "a1", sharedWith = "s1"),
            RecordingSummaryParser.parse("r2", data)
        )
    }

    @Test
    fun `kind video marca el video y cualquier otro valor es audio`() {
        val video = RecordingSummaryParser.parse("v1", mapOf("status" to "ready", "kind" to "video"))
        assertEquals(MediaKind.VIDEO, video?.kind)
        assertEquals(MediaKind.AUDIO, RecordingSummaryParser.parse("r1", mapOf("status" to "ready"))?.kind)
        assertEquals(
            MediaKind.AUDIO,
            RecordingSummaryParser.parse("r1", mapOf("status" to "ready", "kind" to "audio"))?.kind
        )
    }

    @Test
    fun `sin estado reconocible el doc se descarta`() {
        assertNull(RecordingSummaryParser.parse("r3", mapOf("status" to "processing")))
        assertNull(RecordingSummaryParser.parse("r3", emptyMap<String, Any>()))
    }
}
