package com.violinstudio.data.feature.recording.dto

import com.violinstudio.domain.feature.recording.model.QueueState
import com.violinstudio.domain.feature.recording.model.QueuedRecording
import com.violinstudio.domain.feature.recording.model.RecordedTake
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Los campos de B7b (espera de confirmacion y cancelacion) viajan en la cola y una cola de B7a sigue leyendose. */
class QueuedRecordingDtoTest {
    private val take = RecordedTake("r1", "A", "/nb/r1.m4a", 5_000, 1_000, 7)

    @Test
    fun `ida y vuelta conserva uploadedAt confirmRetried y cancelled`() {
        val item = QueuedRecording(
            take,
            state = QueueState.UPLOADED,
            uploadedAt = 123L,
            confirmRetried = true,
            cancelled = true
        )
        assertEquals(item, QueuedRecordingDto.from(item).toDomain())
    }

    @Test
    fun `un elemento escrito por B7a sin los campos nuevos toma los valores por defecto`() {
        val old = """{"id":"r1","uid":"A","path":"/p","durationMs":1,"sizeBytes":2,"consentEpoch":0,"state":"RECORDED"}"""
        val item = Json.decodeFromString(QueuedRecordingDto.serializer(), old).toDomain()!!
        assertNull(item.uploadedAt)
        assertFalse(item.confirmRetried)
        assertFalse(item.cancelled)
    }
}
