package com.violinstudio.data.feature.recording.datasource.media.video

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.violinstudio.domain.feature.recording.model.BlurMask
import com.violinstudio.domain.feature.recording.model.FaceBlurPolicy
import com.violinstudio.domain.feature.recording.model.FaceMaskTracker
import com.violinstudio.domain.feature.recording.model.NormRect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/** Efecto de Media3 que aplica `FaceBlurPolicy` a cada fotograma. Solo SDR: con HDR falla y no se sube nada. */
@OptIn(UnstableApi::class)
internal class FaceBlurEffect : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        if (useHdr) throw VideoFrameProcessingException("El difuminado de caras no admite HDR")
        return try {
            FaceBlurShaderProgram()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }
}

/**
 * Por cada fotograma: copia reducida y al derecho -> ML Kit (modelo incluido, sin red) -> [FaceMaskTracker] -> dibuja
 * el fotograma con las zonas difuminadas. La deteccion ve exactamente el fotograma que se dibuja, asi que un giro o
 * una cara que el detector no encuentre acaban en difuminado completo (falla cerrado). No se prueba en JVM (GL y
 * ML Kit): verificacion en dispositivo pendiente.
 */
@OptIn(UnstableApi::class)
private class FaceBlurShaderProgram : BaseGlShaderProgram(false, 1) {
    private val blurProgram = GlProgram(VERTEX_SHADER, BLUR_FRAGMENT_SHADER)
    private val copyProgram = GlProgram(FLIP_VERTEX_SHADER, COPY_FRAGMENT_SHADER)
    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(MIN_FACE_SIZE)
            .build()
    )
    private val tracker = FaceMaskTracker()
    private val savedFbo = IntArray(1)
    private val savedViewport = IntArray(4)
    private var frameWidth = 0
    private var frameHeight = 0
    private var detectWidth = 0
    private var detectHeight = 0
    private var detectTexId = NO_ID
    private var detectFboId = NO_ID
    private var pixels: ByteBuffer = ByteBuffer.allocateDirect(0)
    private var bitmap: Bitmap? = null

    init {
        val bounds = GlUtil.getNormalizedCoordinateBounds()
        blurProgram.setBufferAttribute("aFramePosition", bounds, GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        copyProgram.setBufferAttribute("aFramePosition", bounds, GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        frameWidth = inputWidth
        frameHeight = inputHeight
        val scale = DETECT_LONG_SIDE.toFloat() / max(inputWidth, inputHeight)
        detectWidth = max(1, (inputWidth * scale).roundToInt())
        detectHeight = max(1, (inputHeight * scale).roundToInt())
        try {
            releaseDetectTarget()
            detectTexId = GlUtil.createTexture(detectWidth, detectHeight, false)
            detectFboId = GlUtil.createFboForTexture(detectTexId)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        pixels = ByteBuffer.allocateDirect(detectWidth * detectHeight * BYTES_PER_PIXEL).order(ByteOrder.nativeOrder())
        bitmap = Bitmap.createBitmap(detectWidth, detectHeight, Bitmap.Config.ARGB_8888)
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val mask = tracker.next(detectFaces(inputTexId))
            blurProgram.use()
            blurProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            val blockPx = max(MIN_BLOCK_PX, minOf(frameWidth, frameHeight) / BLOCKS_PER_SHORT_SIDE)
            blurProgram.setFloatsUniform("uBlock", floatArrayOf(blockPx / frameWidth, blockPx / frameHeight))
            blurProgram.setFloatUniform("uFull", if (mask == BlurMask.FullFrame) 1f else 0f)
            val rects = (mask as? BlurMask.Regions)?.rects.orEmpty()
            for (i in 0 until MAX_UNIFORM_REGIONS) {
                val r = rects.getOrNull(i)
                val values = if (r == null) UNUSED_REGION else floatArrayOf(r.left, r.top, r.right, r.bottom)
                blurProgram.setFloatsUniform("uR$i", values)
            }
            blurProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, QUAD_VERTICES)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    /** Caras del fotograma en coordenadas de imagen; `null` si el detector falla (cuenta como "sin cara"). */
    private fun detectFaces(inputTexId: Int): List<NormRect>? {
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, savedFbo, 0)
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, savedViewport, 0)
        GlUtil.focusFramebufferUsingCurrentContext(detectFboId, detectWidth, detectHeight)
        copyProgram.use()
        copyProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        copyProgram.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, QUAD_VERTICES)
        pixels.rewind()
        GLES20.glReadPixels(0, 0, detectWidth, detectHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        // Vuelve al framebuffer de salida que BaseGlShaderProgram dejo enfocado antes de llamar a drawFrame.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, savedFbo[0])
        GLES20.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3])
        val frame = bitmap ?: return null
        pixels.rewind()
        frame.copyPixelsFromBuffer(pixels)
        return try {
            Tasks.await(detector.process(InputImage.fromBitmap(frame, 0)), DETECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .map { face ->
                    val box = face.boundingBox
                    NormRect(
                        left = box.left.toFloat() / detectWidth,
                        top = box.top.toFloat() / detectHeight,
                        right = box.right.toFloat() / detectWidth,
                        bottom = box.bottom.toFloat() / detectHeight
                    )
                }
        } catch (e: Exception) {
            null
        }
    }

    override fun release() {
        super.release()
        try {
            blurProgram.delete()
            copyProgram.delete()
            releaseDetectTarget()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        } finally {
            detector.close()
            bitmap?.recycle()
            bitmap = null
        }
    }

    private fun releaseDetectTarget() {
        if (detectFboId != NO_ID) GlUtil.deleteFbo(detectFboId)
        if (detectTexId != NO_ID) GlUtil.deleteTexture(detectTexId)
        detectFboId = NO_ID
        detectTexId = NO_ID
    }

    private companion object {
        const val NO_ID = -1
        const val BYTES_PER_PIXEL = 4
        const val QUAD_VERTICES = 4
        const val DETECT_LONG_SIDE = 480
        const val DETECT_TIMEOUT_MS = 2_000L
        const val MIN_FACE_SIZE = 0.05f
        const val MIN_BLOCK_PX = 8f
        const val BLOCKS_PER_SHORT_SIDE = 16f

        /** Debe coincidir con `uR0..uR7` del shader y con `FaceBlurPolicy.MAX_REGIONS`. */
        const val MAX_UNIFORM_REGIONS = FaceBlurPolicy.MAX_REGIONS
        val UNUSED_REGION = floatArrayOf(-1f, -1f, -1f, -1f)

        const val VERTEX_SHADER = """
            attribute vec4 aFramePosition;
            varying vec2 vTexSamplingCoord;
            void main() {
              gl_Position = aFramePosition;
              vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
            }
        """

        /** Copia invertida en vertical: la fila 0 de `glReadPixels` queda como la fila de arriba de la imagen. */
        const val FLIP_VERTEX_SHADER = """
            attribute vec4 aFramePosition;
            varying vec2 vTexSamplingCoord;
            void main() {
              gl_Position = aFramePosition;
              vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, 0.5 - aFramePosition.y * 0.5);
            }
        """

        const val COPY_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexSampler;
            varying vec2 vTexSamplingCoord;
            void main() {
              gl_FragColor = texture2D(uTexSampler, vTexSamplingCoord);
            }
        """

        /**
         * Mosaico promediado (bloques de 1/16 del lado corto, 16 muestras por bloque) dentro de las zonas o en todo el
         * fotograma con `uFull`. Las zonas llegan en coordenadas de imagen (origen arriba): se compara con `1 - y`.
         */
        const val BLUR_FRAGMENT_SHADER = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform sampler2D uTexSampler;
            uniform vec2 uBlock;
            uniform float uFull;
            uniform vec4 uR0;
            uniform vec4 uR1;
            uniform vec4 uR2;
            uniform vec4 uR3;
            uniform vec4 uR4;
            uniform vec4 uR5;
            uniform vec4 uR6;
            uniform vec4 uR7;
            varying vec2 vTexSamplingCoord;
            float inside(vec4 r, vec2 p) {
              return step(r.x, p.x) * step(p.x, r.z) * step(r.y, p.y) * step(p.y, r.w);
            }
            void main() {
              vec2 p = vTexSamplingCoord;
              vec2 img = vec2(p.x, 1.0 - p.y);
              float m = uFull;
              m = max(m, inside(uR0, img));
              m = max(m, inside(uR1, img));
              m = max(m, inside(uR2, img));
              m = max(m, inside(uR3, img));
              m = max(m, inside(uR4, img));
              m = max(m, inside(uR5, img));
              m = max(m, inside(uR6, img));
              m = max(m, inside(uR7, img));
              if (m < 0.5) {
                gl_FragColor = texture2D(uTexSampler, p);
                return;
              }
              vec2 origin = floor(p / uBlock) * uBlock;
              vec4 sum = vec4(0.0);
              for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                  vec2 offset = (vec2(float(i), float(j)) + 0.5) * 0.25 * uBlock;
                  sum += texture2D(uTexSampler, clamp(origin + offset, 0.0, 1.0));
                }
              }
              gl_FragColor = sum / 16.0;
            }
        """
    }
}
