package com.violinstudio.domain.feature.recording.model

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Politica de difuminado del video (4c): solo caras con 30 % de margen y fotograma entero si no hay cara. */
class FaceBlurPolicyTest {
    private val face = NormRect(left = 0.4f, top = 0.2f, right = 0.6f, bottom = 0.5f)

    private fun assertRect(expected: NormRect, actual: NormRect) {
        listOf(
            expected.left to actual.left,
            expected.top to actual.top,
            expected.right to actual.right,
            expected.bottom to actual.bottom
        ).forEach { (e, a) -> assertEquals(e, a, 1e-5f) }
    }

    @Test
    fun `la caja crece un 30 por ciento de su ancho y su alto por cada lado`() {
        assertRect(NormRect(0.34f, 0.11f, 0.66f, 0.59f), FaceBlurPolicy.expand(face))
    }

    @Test
    fun `la caja ampliada se recorta al fotograma`() {
        val corner = NormRect(0f, 0f, 0.2f, 0.2f)
        assertRect(NormRect(0f, 0f, 0.26f, 0.26f), FaceBlurPolicy.expand(corner))
        val edge = NormRect(0.9f, 0.8f, 1f, 1f)
        assertRect(NormRect(0.87f, 0.74f, 1f, 1f), FaceBlurPolicy.expand(edge))
    }

    @Test
    fun `sin cara detectada se difumina el fotograma entero`() {
        assertEquals(BlurMask.FullFrame, FaceMaskTracker().next(emptyList()))
    }

    @Test
    fun `un fallo del detector cuenta como sin cara`() {
        assertEquals(BlurMask.FullFrame, FaceMaskTracker().next(null))
    }

    @Test
    fun `cajas vacias o degeneradas no cuentan como cara`() {
        val degenerate = listOf(NormRect(0.5f, 0.5f, 0.5f, 0.6f), NormRect(0.5f, 0.6f, 0.6f, 0.5f))
        assertEquals(BlurMask.FullFrame, FaceMaskTracker().next(degenerate))
    }

    @Test
    fun `con cara se difumina solo la cara ampliada`() {
        val mask = FaceMaskTracker().next(listOf(face))
        assertTrue(mask is BlurMask.Regions)
        val rects = (mask as BlurMask.Regions).rects
        assertEquals(1, rects.size)
        assertRect(FaceBlurPolicy.expand(face), rects.single())
    }

    @Test
    fun `se suman las caras del fotograma anterior para cubrir el movimiento`() {
        val tracker = FaceMaskTracker()
        val moved = NormRect(0.45f, 0.2f, 0.65f, 0.5f)
        tracker.next(listOf(face))
        val mask = tracker.next(listOf(moved)) as BlurMask.Regions
        assertEquals(listOf(FaceBlurPolicy.expand(moved), FaceBlurPolicy.expand(face)), mask.rects)
    }

    @Test
    fun `un fotograma sin cara entre dos con cara se difumina entero y no arrastra cajas viejas`() {
        val tracker = FaceMaskTracker()
        tracker.next(listOf(face))
        assertEquals(BlurMask.FullFrame, tracker.next(emptyList()))
        val after = tracker.next(listOf(face)) as BlurMask.Regions
        assertEquals(1, after.rects.size)
    }

    @Test
    fun `mas zonas de las que dibuja el shader difumina el fotograma entero`() {
        val crowd = (0 until FaceBlurPolicy.MAX_REGIONS + 1).map { i ->
            val x = i / 10f
            NormRect(x, 0.1f, x + 0.05f, 0.2f)
        }
        assertEquals(BlurMask.FullFrame, FaceMaskTracker().next(crowd))
        val tracker = FaceMaskTracker()
        tracker.next(crowd.take(5))
        assertEquals(BlurMask.FullFrame, tracker.next(crowd.take(4)))
    }

    @Test
    fun `la version de la politica es la del sello que exige el servidor`() {
        val server = File("../backend/functions/src/config/recordings.ts").readText()
        val version = Regex("""export const BLUR_POLICY_VERSION\s*=\s*([0-9]+);""").find(server)?.groupValues?.get(1)
        assertEquals(version?.toInt(), FaceBlurPolicy.VERSION)
        assertEquals(1, FaceBlurPolicy.VERSION)
        assertEquals(0.30f, FaceBlurPolicy.FACE_MARGIN)
    }
}
