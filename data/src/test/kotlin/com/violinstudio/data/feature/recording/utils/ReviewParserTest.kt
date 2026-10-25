package com.violinstudio.data.feature.recording.utils

import com.google.firebase.Timestamp
import com.violinstudio.domain.feature.recording.model.Review
import com.violinstudio.domain.feature.recording.model.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReviewParserTest {
    @Test
    fun `una revision del profesor se lee entera`() {
        val data = mapOf(
            "reviewerUid" to "t1",
            "verdict" to "needs_work",
            "text" to "Cuida el arco",
            "updatedAt" to Timestamp(10, 5_000_000)
        )
        assertEquals(Review(Verdict.NEEDS_WORK, "Cuida el arco", 10_005), ReviewParser.parse(data))
    }

    @Test
    fun `una anonimizada no tiene autor y sin veredicto valido se descarta`() {
        val anonymous = ReviewParser.parse(mapOf("reviewerUid" to null, "verdict" to "approved"))
        assertEquals(Review(Verdict.APPROVED, "", null, fromTeacher = false), anonymous)
        assertNull(ReviewParser.parse(mapOf("verdict" to "ok")))
        assertNull(ReviewParser.parse(null))
    }

    @Test
    fun `los veredictos coinciden con las reglas`() {
        val rules = java.io.File("../backend/firestore.rules").readText()
        val wires = Verdict.entries.joinToString(", ") { "'${it.wire}'" }
        assertEquals(true, "d.verdict in [$wires]" in rules)
        assertEquals(true, "d.text.size() <= ${Review.MAX_TEXT}" in rules)
    }
}
