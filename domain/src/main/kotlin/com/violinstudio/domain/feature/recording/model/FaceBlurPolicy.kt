package com.violinstudio.domain.feature.recording.model

/**
 * Rectangulo normalizado a `[0, 1]` con origen arriba a la izquierda (el de la imagen, no el de OpenGL).
 * Lo produce el detector de caras sobre una copia reducida del fotograma.
 */
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val isEmpty: Boolean get() = !(right > left && bottom > top)
}

/** Que se difumina en un fotograma: todo, o solo unas zonas (las caras con su margen). */
sealed interface BlurMask {
    data object FullFrame : BlurMask

    data class Regions(val rects: List<NormRect>) : BlurMask
}

/**
 * Politica de difuminado del video (4c, decisiones de David del 2026-10-21): solo caras, con un margen del 30 %;
 * obligatorio tambien para adultos; un fotograma sin cara detectada se difumina entero. Cambiar algo de aqui obliga
 * a subir [RecordingLimits.BLUR_POLICY_VERSION] (y la del servidor), porque el sello promete esta politica.
 */
object FaceBlurPolicy {
    const val VERSION = RecordingLimits.BLUR_POLICY_VERSION

    /** Margen por cada lado, como fraccion del ancho y del alto de la caja de la cara. */
    const val FACE_MARGIN = 0.30f

    /** Zonas que dibuja el shader; con mas caras se difumina el fotograma entero (falla cerrado). */
    const val MAX_REGIONS = 8

    /** Caja de la cara ampliada con [FACE_MARGIN] por cada lado y recortada al fotograma. */
    fun expand(face: NormRect): NormRect {
        val dx = (face.right - face.left) * FACE_MARGIN
        val dy = (face.bottom - face.top) * FACE_MARGIN
        return NormRect(
            left = (face.left - dx).coerceIn(0f, 1f),
            top = (face.top - dy).coerceIn(0f, 1f),
            right = (face.right + dx).coerceIn(0f, 1f),
            bottom = (face.bottom + dy).coerceIn(0f, 1f)
        )
    }
}

/**
 * Decide la mascara fotograma a fotograma. `faces = null` es un fallo del detector y cuenta como "sin cara": se
 * difumina todo. Las caras del fotograma anterior se suman a las del actual para cubrir un movimiento rapido entre
 * dos detecciones. No es seguro entre hilos: una instancia por exportacion.
 */
class FaceMaskTracker {
    private var previous: List<NormRect> = emptyList()

    fun next(faces: List<NormRect>?): BlurMask {
        val current = faces.orEmpty().filterNot { it.isEmpty }.map(FaceBlurPolicy::expand)
        val regions = current + previous
        previous = current
        return if (current.isEmpty() || regions.size > FaceBlurPolicy.MAX_REGIONS) {
            BlurMask.FullFrame
        } else {
            BlurMask.Regions(regions)
        }
    }
}
