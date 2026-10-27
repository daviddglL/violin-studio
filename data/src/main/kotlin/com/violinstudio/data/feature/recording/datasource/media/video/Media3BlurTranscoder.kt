package com.violinstudio.data.feature.recording.datasource.media.video

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Adaptador fino sobre Media3 Transformer: H.264 a [VIDEO_BITRATE_BPS] con [FaceBlurEffect] en cada fotograma; el audio
 * se copia tal cual.
 * Transformer exige un `Looper`: cada exportacion usa su propio hilo y lo cierra al terminar. Sin logica propia ni
 * logs; no se prueba en JVM.
 */
@OptIn(UnstableApi::class)
class Media3BlurTranscoder @Inject constructor(@param:ApplicationContext private val context: Context) :
    BlurTranscoder {
    override suspend fun export(source: File, output: File): Long {
        val thread = HandlerThread("video-blur").apply { start() }
        val handler = Handler(thread.looper)
        try {
            return suspendCancellableCoroutine { cont ->
                var transformer: Transformer? = null
                handler.post {
                    val listener = object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            cont.resume(exportResult.durationMs)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            cont.resumeWithException(exportException)
                        }
                    }
                    val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source)))
                        .setEffects(Effects(emptyList(), listOf(FaceBlurEffect())))
                        .build()
                    try {
                        transformer = Transformer.Builder(context)
                            .setLooper(thread.looper)
                            .setVideoMimeType(MimeTypes.VIDEO_H264)
                            .setEncoderFactory(
                                DefaultEncoderFactory.Builder(context)
                                    .setRequestedVideoEncoderSettings(
                                        VideoEncoderSettings.Builder().setBitrate(VIDEO_BITRATE_BPS).build()
                                    )
                                    .build()
                            )
                            .addListener(listener)
                            .build()
                            .also { it.start(item, output.path) }
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    }
                }
                cont.invokeOnCancellation { handler.post { transformer?.cancel() } }
            }
        } finally {
            thread.quitSafely()
        }
    }

    private companion object {
        /** 3 Mbps: 120 s de profesor caben en ~45 MB, bajo `MAX_VIDEO_BYTES` (60 MiB) y la tasa maxima del servidor. */
        const val VIDEO_BITRATE_BPS = 3_000_000
    }
}
