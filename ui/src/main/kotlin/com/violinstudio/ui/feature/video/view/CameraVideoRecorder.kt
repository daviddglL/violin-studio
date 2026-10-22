package com.violinstudio.ui.feature.video.view

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File

/**
 * Camara de la pantalla de video (4c): vista previa y grabacion del original a 720p como mucho, con sonido. Va ligada
 * al ciclo de vida de la pantalla: al salir de primer plano CameraX cierra la camara y la toma acaba con error.
 * El original solo se escribe en la ruta que da `StartVideoUseCase`; difuminarlo y borrarlo es cosa del dominio.
 */
class CameraVideoRecorder(private val context: Context) {
    private val recorder = Recorder.Builder()
        .setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityThan(Quality.HD)))
        .build()
    private val videoCapture = VideoCapture.withOutput(recorder)
    private var recording: Recording? = null
    private var provider: ProcessCameraProvider? = null

    /** Enlaza vista previa y captura a [owner]; prefiere la camara frontal (el alumno se ve mientras toca). */
    fun bind(owner: LifecycleOwner, surface: Preview.SurfaceProvider) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                // Sin camara utilizable no hay vista previa y "Grabar" acaba en CAMERA_FAILED.
                runCatching {
                    val cameras = future.get()
                    provider = cameras
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(surface) }
                    val selector = if (cameras.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }
                    cameras.unbindAll()
                    cameras.bindToLifecycle(owner, selector, preview, videoCapture)
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    /**
     * Graba en [rawPath] hasta [maxDurationMs] (tope de seguridad: el ViewModel para antes). [onFinalized] recibe la
     * ruta y si el fichero sirve; llega siempre, tambien si no se pudo empezar.
     */
    @SuppressLint("MissingPermission")
    fun start(rawPath: String, maxDurationMs: Long, onFinalized: (String, Boolean) -> Unit) {
        val permitted = PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!permitted || recording != null) return onFinalized(rawPath, false)
        val options = FileOutputOptions.Builder(File(rawPath)).setDurationLimitMillis(maxDurationMs).build()
        recording = try {
            recorder.prepareRecording(context, options)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    if (event is VideoRecordEvent.Finalize) {
                        recording = null
                        onFinalized(rawPath, event.isUsable())
                    }
                }
        } catch (_: IllegalStateException) {
            null
        } catch (_: SecurityException) {
            null
        }
        if (recording == null) onFinalized(rawPath, false)
    }

    fun stop() {
        recording?.stop()
    }

    /** Cierra la toma (salir de la pantalla); el ViewModel ya la dio por descartada y borra el fichero al cerrarse. */
    fun cancel() {
        recording?.close()
    }

    fun release() {
        cancel()
        provider?.unbindAll()
        provider = null
    }

    private fun VideoRecordEvent.Finalize.isUsable(): Boolean =
        !hasError() || error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED

    companion object {
        val PERMISSIONS = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }
}
