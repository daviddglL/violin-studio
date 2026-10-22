package com.violinstudio.ui.feature.video.viewmodel

import com.violinstudio.ui.commons.mvi.UiEffect
import com.violinstudio.ui.commons.mvi.UiIntent
import com.violinstudio.ui.commons.mvi.UiState

/** [STARTING]: comprobando permisos del servidor; [STOPPING]: la camara cierra el fichero; [BLURRING]: difuminando. */
enum class VideoPhase { IDLE, STARTING, RECORDING, STOPPING, BLURRING }

enum class VideoMessage {
    NOT_AVAILABLE,
    OFFLINE,
    QUOTA_REACHED,
    CAMERA_FAILED,
    TOO_SHORT,
    TOO_LARGE,
    BLUR_FAILED,
    SAVE_FAILED
}

/** Permisos de camara y micro: [RATIONALE] explica antes de volver a pedirlos; [BLOCKED] solo se arregla en Ajustes. */
enum class CameraPrompt { RATIONALE, BLOCKED }

/** [maxDurationMs] no nulo desde que hay toma; [demo] graba una demostracion del profesor para un alumno. */
data class VideoCaptureState(
    val phase: VideoPhase = VideoPhase.IDLE,
    val elapsedMs: Long = 0,
    val maxDurationMs: Long? = null,
    val prompt: CameraPrompt? = null,
    val message: VideoMessage? = null,
    val demo: Boolean = false
) : UiState {
    val isCapturing: Boolean get() = phase == VideoPhase.RECORDING || phase == VideoPhase.STOPPING
    val isBusy: Boolean get() = phase != VideoPhase.IDLE
}

sealed interface VideoCaptureIntent : UiIntent {
    /** "Grabar"; [granted] y [rationale] los lee la pantalla para camara y micro juntos. */
    data class Start(val granted: Boolean, val rationale: Boolean) : VideoCaptureIntent

    data class PermissionResult(val granted: Boolean, val rationale: Boolean) : VideoCaptureIntent

    data object ConfirmRationale : VideoCaptureIntent

    data object DismissPrompt : VideoCaptureIntent

    data object OpenAppSettings : VideoCaptureIntent

    data object Stop : VideoCaptureIntent

    /**
     * La camara cerro el fichero [rawPath]: [ok] `false` si la toma no sirve (error de camara o grabacion
     * interrumpida). La ruta distingue el cierre tardio de una toma ya descartada del de la toma en curso.
     */
    data class CaptureFinalized(val rawPath: String, val ok: Boolean) : VideoCaptureIntent {
        override fun toString(): String = "CaptureFinalized(ok=$ok)"
    }

    /** `ON_STOP` o salir de la pantalla: una toma en curso se descarta (no hay grabacion en segundo plano). */
    data object Leave : VideoCaptureIntent

    data object DismissMessage : VideoCaptureIntent
}

sealed interface VideoCaptureEffect : UiEffect {
    data object RequestPermissions : VideoCaptureEffect

    data object OpenAppSettings : VideoCaptureEffect

    /** Grabar el original en [rawPath] (directorio sin copia de seguridad) como mucho [maxDurationMs]. */
    data class BeginCapture(val rawPath: String, val maxDurationMs: Long) : VideoCaptureEffect {
        override fun toString(): String = "BeginCapture"
    }

    data object EndCapture : VideoCaptureEffect

    data object CancelCapture : VideoCaptureEffect

    /** Toma difuminada y en la cola de subida: se vuelve a la lista de grabaciones. */
    data object Saved : VideoCaptureEffect
}

sealed interface VideoCaptureMutation {
    data object Starting : VideoCaptureMutation

    data class Recording(val maxDurationMs: Long) : VideoCaptureMutation

    data class Tick(val elapsedMs: Long) : VideoCaptureMutation

    data object Stopping : VideoCaptureMutation

    data object Blurring : VideoCaptureMutation

    data object Idle : VideoCaptureMutation

    data class Prompt(val prompt: CameraPrompt?) : VideoCaptureMutation

    data class Message(val message: VideoMessage?) : VideoCaptureMutation
}
