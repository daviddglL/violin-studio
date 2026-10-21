package com.violinstudio.domain.feature.recording.model

import com.violinstudio.domain.feature.profile.model.Role
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** REQ-REC-01: los limites del cliente (una sola definicion) coinciden con `functions/src/config/recordings.ts`. */
class RecordingLimitsTest {
    private val serverSource = File("../functions/src/config/recordings.ts").readText()

    private fun serverNumber(name: String): Double {
        val raw = Regex("""export const $name\s*=\s*([0-9_.* ]+);""").find(serverSource)?.groupValues?.get(1)
        assertNotNull(raw, "$name no encontrada en recordings.ts")
        return raw!!.replace("_", "").split("*").map { it.trim().toDouble() }.reduce { a, b -> a * b }
    }

    @Test
    fun `los limites coinciden con la constante de Functions`() {
        assertEquals(serverNumber("MAX_STUDENT_DURATION_SEC").toLong() * 1000, RecordingLimits.STUDENT_MAX_MS)
        assertEquals(serverNumber("MAX_TEACHER_DURATION_SEC").toLong() * 1000, RecordingLimits.TEACHER_MAX_MS)
        assertEquals(serverNumber("MAX_AUDIO_BYTES").toLong(), RecordingLimits.MAX_AUDIO_BYTES)
        assertEquals(serverNumber("MAX_VIDEO_BYTES").toLong(), RecordingLimits.MAX_VIDEO_BYTES)
        assertEquals(serverNumber("BLUR_POLICY_VERSION").toInt(), RecordingLimits.BLUR_POLICY_VERSION)
        assertEquals(serverNumber("MAX_RECORDINGS_PER_OWNER").toInt(), RecordingLimits.MAX_RECORDINGS_PER_OWNER)
    }

    @Test
    fun `la duracion minima coincide con la de Functions y es 500 ms`() {
        assertEquals((serverNumber("MIN_DURATION_SEC") * 1000).toLong(), RecordingLimits.MIN_DURATION_MS)
        assertEquals(500L, RecordingLimits.MIN_DURATION_MS)
    }

    @Test
    fun `el valor literal es 60 s, 120 s y 25 MiB`() {
        assertEquals(60_000L, RecordingLimits.STUDENT_MAX_MS)
        assertEquals(120_000L, RecordingLimits.TEACHER_MAX_MS)
        assertEquals(25L * 1024 * 1024, RecordingLimits.MAX_AUDIO_BYTES)
        assertEquals(60L * 1024 * 1024, RecordingLimits.MAX_VIDEO_BYTES)
        assertEquals(20, RecordingLimits.MAX_RECORDINGS_PER_OWNER)
    }

    @Test
    fun `la parada automatica deja la duracion dentro de la tolerancia del servidor`() {
        val toleranceMs = (serverNumber("DURATION_TOLERANCE_SEC") * 1000).toLong()
        assertEquals(toleranceMs, RecordingLimits.SERVER_TOLERANCE_MS)
        // Parar a <= limite - tolerancia deja que el redondeo del contenedor nunca supere limite + tolerancia.
        assertEquals(59_500L, RecordingLimits.autoStopMs(Role.STUDENT))
        assertEquals(119_500L, RecordingLimits.autoStopMs(Role.TEACHER))
        Role.entries.forEach {
            assertTrue(RecordingLimits.autoStopMs(it) <= RecordingLimits.maxDurationMs(it) - toleranceMs)
        }
    }

    @Test
    fun `el limite es por rol y solo el profesor tiene 120 s`() {
        assertEquals(60_000L, RecordingLimits.maxDurationMs(Role.STUDENT))
        assertEquals(60_000L, RecordingLimits.maxDurationMs(Role.INDEPENDENT))
        assertEquals(120_000L, RecordingLimits.maxDurationMs(Role.TEACHER))
    }
}
