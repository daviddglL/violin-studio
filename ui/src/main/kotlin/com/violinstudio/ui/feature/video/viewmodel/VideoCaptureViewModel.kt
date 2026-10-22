package com.violinstudio.ui.feature.video.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.recording.failure.RecordingFailure
import com.violinstudio.domain.feature.recording.model.VideoSpec
import com.violinstudio.domain.feature.recording.usecase.DiscardVideoUseCase
import com.violinstudio.domain.feature.recording.usecase.FinishVideoUseCase
import com.violinstudio.domain.feature.recording.usecase.StartVideoUseCase
import com.violinstudio.ui.commons.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Grabar un video (4c). [StartVideoUseCase] repite todas las comprobaciones y da la ruta del original; la pantalla
 * graba con la camara y avisa al cerrar el fichero; [FinishVideoUseCase] difumina, borra el original y encola solo
 * el difuminado. Salir de la pantalla descarta la toma: no se graba en segundo plano.
 *
 * Con `studentUid` y `assignmentId` en la ruta es una demostracion del profesor para ese alumno y esa tarea.
 */
@HiltViewModel
class VideoCaptureViewModel @Inject constructor(
    private val startVideo: StartVideoUseCase,
    private val finishVideo: FinishVideoUseCase,
    private val discardVideo: DiscardVideoUseCase,
    savedStateHandle: SavedStateHandle
) : MviViewModel<VideoCaptureState, VideoCaptureIntent, VideoCaptureEffect>(
    VideoCaptureState(demo = DemoTarget.from(savedStateHandle) != null)
) {
    private val demo: DemoTarget? = DemoTarget.from(savedStateHandle)

    /** Toma en curso (grabando, cerrando o difuminando). */
    private var active: VideoSpec? = null

    /** Toma descartada cuya camara aun puede escribir el original: se vuelve a borrar al cerrar el fichero. */
    private var abandoned: VideoSpec? = null
    private var ticker: Job? = null

    override suspend fun handleIntent(intent: VideoCaptureIntent) {
        when (intent) {
            is VideoCaptureIntent.Start -> onStart(intent.granted, intent.rationale)
            is VideoCaptureIntent.PermissionResult -> onPermission(intent.granted, intent.rationale)
            VideoCaptureIntent.ConfirmRationale -> {
                reduce(VideoCaptureMutation.Prompt(null))
                sendEffect(VideoCaptureEffect.RequestPermissions)
            }
            VideoCaptureIntent.DismissPrompt -> reduce(VideoCaptureMutation.Prompt(null))
            VideoCaptureIntent.OpenAppSettings -> sendEffect(VideoCaptureEffect.OpenAppSettings)
            VideoCaptureIntent.Stop -> onStop()
            is VideoCaptureIntent.CaptureFinalized -> onFinalized(intent.rawPath, intent.ok)
            VideoCaptureIntent.Leave -> onLeave()
            VideoCaptureIntent.DismissMessage -> reduce(VideoCaptureMutation.Message(null))
        }
    }

    private suspend fun onStart(granted: Boolean, rationale: Boolean) {
        if (state.value.isBusy) return
        when {
            granted -> begin()
            rationale -> reduce(VideoCaptureMutation.Prompt(CameraPrompt.RATIONALE))
            else -> sendEffect(VideoCaptureEffect.RequestPermissions)
        }
    }

    private suspend fun onPermission(granted: Boolean, rationale: Boolean) {
        if (granted) {
            reduce(VideoCaptureMutation.Prompt(null))
            if (!state.value.isBusy) begin()
        } else {
            reduce(VideoCaptureMutation.Prompt(if (rationale) CameraPrompt.RATIONALE else CameraPrompt.BLOCKED))
        }
    }

    private suspend fun begin() {
        reduce(VideoCaptureMutation.Starting)
        val spec = startVideo().getOrElse { error ->
            reduce(VideoCaptureMutation.Idle)
            return reduce(VideoCaptureMutation.Message(startMessage(error)))
        }
        active = spec
        reduce(VideoCaptureMutation.Recording(spec.maxDurationMs))
        sendEffect(VideoCaptureEffect.BeginCapture(spec.rawPath, spec.maxDurationMs))
        ticker = viewModelScope.launch {
            var elapsed = 0L
            while (elapsed < spec.autoStopMs) {
                delay(TICK_MS)
                elapsed += TICK_MS
                reduce(VideoCaptureMutation.Tick(elapsed))
            }
            // Parada automatica al limite: se resuelve como si el usuario pulsara "Parar".
            onIntent(VideoCaptureIntent.Stop)
        }
    }

    private fun onStop() {
        if (state.value.phase != VideoPhase.RECORDING) return
        stopTicker()
        reduce(VideoCaptureMutation.Stopping)
        sendEffect(VideoCaptureEffect.EndCapture)
    }

    private suspend fun onFinalized(rawPath: String, ok: Boolean) {
        abandoned?.takeIf { it.rawPath == rawPath }?.let { spec ->
            abandoned = null
            return discardVideo(spec)
        }
        val spec = active?.takeIf { it.rawPath == rawPath } ?: return
        if (!state.value.isCapturing) return
        stopTicker()
        if (!ok) {
            active = null
            discardVideo(spec)
            reduce(VideoCaptureMutation.Idle)
            return reduce(VideoCaptureMutation.Message(VideoMessage.CAMERA_FAILED))
        }
        reduce(VideoCaptureMutation.Blurring)
        val result = finishVideo(spec, demo?.assignmentId, demo?.studentUid)
        active = null
        reduce(VideoCaptureMutation.Idle)
        result.fold(
            onSuccess = { sendEffect(VideoCaptureEffect.Saved) },
            onFailure = { reduce(VideoCaptureMutation.Message(finishMessage(it))) }
        )
    }

    private suspend fun onLeave() {
        val spec = active ?: return
        if (!state.value.isCapturing) return
        stopTicker()
        active = null
        abandoned = spec
        sendEffect(VideoCaptureEffect.CancelCapture)
        discardVideo(spec)
        reduce(VideoCaptureMutation.Idle)
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun startMessage(error: Throwable): VideoMessage = when (error) {
        RecordingFailure.QuotaReached -> VideoMessage.QUOTA_REACHED
        RecordingFailure.Unavailable -> VideoMessage.OFFLINE
        else -> VideoMessage.NOT_AVAILABLE
    }

    private fun finishMessage(error: Throwable): VideoMessage = when (error) {
        RecordingFailure.TooShort -> VideoMessage.TOO_SHORT
        RecordingFailure.TooLarge -> VideoMessage.TOO_LARGE
        RecordingFailure.BlurFailed -> VideoMessage.BLUR_FAILED
        else -> VideoMessage.SAVE_FAILED
    }

    /** Si el ViewModel muere con una toma abierta (sin pasar por `Leave`), se borra fuera de su ambito. */
    @OptIn(DelicateCoroutinesApi::class)
    override fun onCleared() {
        listOfNotNull(active, abandoned).forEach { spec -> GlobalScope.launch { discardVideo(spec) } }
        active = null
        abandoned = null
        super.onCleared()
    }

    private fun reduce(mutation: VideoCaptureMutation) = setState { VideoCaptureReducer.reduce(this, mutation) }

    companion object {
        const val TICK_MS = 250L
        const val STUDENT_UID_KEY = "studentUid"
        const val ASSIGNMENT_ID_KEY = "assignmentId"
    }
}

/** Alumno y tarea de una demo; los nombres son los argumentos de `VideoCaptureDestination`. */
private data class DemoTarget(val studentUid: String, val assignmentId: String) {
    override fun toString(): String = "DemoTarget"

    companion object {
        fun from(handle: SavedStateHandle): DemoTarget? {
            val student = handle.get<String>(VideoCaptureViewModel.STUDENT_UID_KEY) ?: return null
            val assignment = handle.get<String>(VideoCaptureViewModel.ASSIGNMENT_ID_KEY) ?: return null
            return DemoTarget(student, assignment)
        }
    }
}
