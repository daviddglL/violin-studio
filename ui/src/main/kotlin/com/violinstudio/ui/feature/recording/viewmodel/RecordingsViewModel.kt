package com.violinstudio.ui.feature.recording.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.consent.failure.ConsentFailure
import com.violinstudio.domain.feature.recording.failure.RecordingFailure
import com.violinstudio.domain.feature.recording.model.RecordingAccess
import com.violinstudio.domain.feature.recording.model.RecordingsOverview
import com.violinstudio.domain.feature.recording.repository.RecordingHandle
import com.violinstudio.domain.feature.recording.usecase.DeleteRecordingUseCase
import com.violinstudio.domain.feature.recording.usecase.DiscardUploadUseCase
import com.violinstudio.domain.feature.recording.usecase.FinishRecordingUseCase
import com.violinstudio.domain.feature.recording.usecase.GetRecordingAccessUseCase
import com.violinstudio.domain.feature.recording.usecase.GrantRecordingConsentUseCase
import com.violinstudio.domain.feature.recording.usecase.ObserveRecordingReviewsUseCase
import com.violinstudio.domain.feature.recording.usecase.ObserveRecordingsUseCase
import com.violinstudio.domain.feature.recording.usecase.RecordingPlaybackUseCase
import com.violinstudio.domain.feature.recording.usecase.RequestRecordingGuardianUseCase
import com.violinstudio.domain.feature.recording.usecase.RetryUploadUseCase
import com.violinstudio.domain.feature.recording.usecase.RevokeRecordingConsentUseCase
import com.violinstudio.domain.feature.recording.usecase.StartRecordingUseCase
import com.violinstudio.ui.commons.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Pantalla de grabaciones (REQ-REC-02/04/09): consentimiento, grabar con la pantalla visible, cola de subida y
 * grabaciones del servidor. El micro solo se abre con [StartRecordingUseCase], que repite todas las comprobaciones; al
 * salir de la pantalla la toma en curso se descarta. La parada automatica llega como un "Parar" mas.
 *
 * Con `studentUid` y `assignmentId` en la ruta (`TeacherDemoDestination`) es el modo demo del profesor: cada toma se
 * comparte con ese alumno ligada a esa tarea y la lista muestra solo esas demos.
 */
@HiltViewModel
class RecordingsViewModel @Inject constructor(
    private val getAccess: GetRecordingAccessUseCase,
    private val grantConsent: GrantRecordingConsentUseCase,
    private val revokeConsent: RevokeRecordingConsentUseCase,
    private val startRecording: StartRecordingUseCase,
    private val finishRecording: FinishRecordingUseCase,
    observeRecordings: ObserveRecordingsUseCase,
    private val retryUpload: RetryUploadUseCase,
    private val discardUpload: DiscardUploadUseCase,
    private val deleteRecording: DeleteRecordingUseCase,
    private val playback: RecordingPlaybackUseCase,
    private val observeReviews: ObserveRecordingReviewsUseCase,
    private val requestGuardian: RequestRecordingGuardianUseCase,
    savedStateHandle: SavedStateHandle
) : MviViewModel<RecordingsState, RecordingsIntent, RecordingsEffect>(
    RecordingsState(demo = DemoTarget.from(savedStateHandle) != null)
) {
    private val demo: DemoTarget? = DemoTarget.from(savedStateHandle)
    private var handle: RecordingHandle? = null
    private var session: Job? = null
    private var playJob: Job? = null
    private var reviewsJob: Job? = null

    init {
        viewModelScope.launch {
            observeRecordings()
                .catch { reduce(RecordingsMutation.Message(RecordingsMessage.LOAD_FAILED)) }
                .collect { reduce(RecordingsMutation.Overview(it.onlyDemo())) }
        }
        viewModelScope.launch { playback.state.collect { reduce(RecordingsMutation.Playback(it)) } }
        onIntent(RecordingsIntent.Refresh)
    }

    override suspend fun handleIntent(intent: RecordingsIntent) {
        when (intent) {
            RecordingsIntent.Refresh -> refresh()
            RecordingsIntent.GrantConsent -> onGrant()
            RecordingsIntent.AskRevoke -> reduce(RecordingsMutation.RevokePrompt(true))
            RecordingsIntent.CancelRevoke -> reduce(RecordingsMutation.RevokePrompt(false))
            RecordingsIntent.ConfirmRevoke -> onRevoke()
            is RecordingsIntent.Start -> onStart(intent.granted, intent.rationale)
            is RecordingsIntent.PermissionResult -> onPermission(intent.granted, intent.rationale)
            RecordingsIntent.ConfirmRationale -> {
                reduce(RecordingsMutation.Mic(null))
                sendEffect(RecordingsEffect.RequestMicPermission)
            }
            RecordingsIntent.DismissMic -> reduce(RecordingsMutation.Mic(null))
            RecordingsIntent.OpenAppSettings -> sendEffect(RecordingsEffect.OpenAppSettings)
            RecordingsIntent.Stop -> onStop()
            RecordingsIntent.Leave -> onLeave()
            is RecordingsIntent.Retry -> withUid { uid -> retryUpload(uid, intent.id) }
            is RecordingsIntent.Discard -> withUid { uid -> discardUpload(uid, intent.id) }
            is RecordingsIntent.AskDelete -> reduce(RecordingsMutation.DeletePrompt(intent.id))
            RecordingsIntent.CancelDelete -> reduce(RecordingsMutation.DeletePrompt(null))
            RecordingsIntent.ConfirmDelete -> onDelete()
            RecordingsIntent.DismissMessage -> reduce(RecordingsMutation.Message(null))
            is RecordingsIntent.Play -> onPlay(intent.id)
            RecordingsIntent.StopPlayback -> stopPlayback()
            is RecordingsIntent.ToggleReviews -> onToggleReviews(intent.id)
            is RecordingsIntent.SetGuardianEmail -> reduce(RecordingsMutation.GuardianEmail(intent.email))
            RecordingsIntent.RequestGuardian -> onRequestGuardian()
        }
    }

    private suspend fun refresh() {
        reduce(RecordingsMutation.AccessLoading)
        getAccess().fold(
            onSuccess = { reduce(RecordingsMutation.AccessLoaded(it)) },
            onFailure = { reduce(RecordingsMutation.AccessFailed) }
        )
    }

    private suspend fun onGrant() {
        val access = state.value.access as? RecordingAccess.NeedsConsent ?: return
        if (!access.selfGrant) return
        busy {
            grantConsent(access.version).fold(onSuccess = { refresh() }, onFailure = { onConsentFailure(it) })
        }
    }

    private suspend fun onRequestGuardian() {
        val access = state.value.access as? RecordingAccess.NeedsConsent ?: return
        if (access.selfGrant) return
        busy {
            requestGuardian(state.value.guardianEmail).fold(
                onSuccess = { masked ->
                    reduce(RecordingsMutation.GuardianSent(masked))
                    refresh()
                },
                onFailure = { onConsentFailure(it) }
            )
        }
    }

    private suspend fun onRevoke() {
        reduce(RecordingsMutation.RevokePrompt(false))
        onLeave()
        busy { revokeConsent().fold(onSuccess = { refresh() }, onFailure = { onConsentFailure(it) }) }
    }

    private suspend fun onConsentFailure(error: Throwable) {
        val message = when (error) {
            ConsentFailure.GuardianRequired -> RecordingsMessage.GUARDIAN_REQUIRED
            ConsentFailure.TemporarilyUnavailable -> RecordingsMessage.TEMPORARILY_UNAVAILABLE
            ConsentFailure.Network -> RecordingsMessage.OFFLINE
            ConsentFailure.GuardianEmailInvalid -> RecordingsMessage.GUARDIAN_EMAIL_INVALID
            ConsentFailure.GuardianEmailMismatch -> RecordingsMessage.GUARDIAN_MISMATCH
            ConsentFailure.GuardianLegacyConsent -> RecordingsMessage.GUARDIAN_LEGACY
            is ConsentFailure.RateLimited -> RecordingsMessage.RATE_LIMITED
            is ConsentFailure.InvalidArgument -> if (error.field == "guardianEmail") {
                RecordingsMessage.GUARDIAN_EMAIL_INVALID
            } else {
                RecordingsMessage.CONSENT_FAILED
            }
            else -> RecordingsMessage.CONSENT_FAILED
        }
        reduce(RecordingsMutation.Message(message))
        // Lo que cambia el estado del servidor (flags, version, ya concedido) se vuelve a leer.
        if (error is ConsentFailure.RecordingDisabled || error is ConsentFailure.RecordingConsentOutdated ||
            error == ConsentFailure.AlreadyGranted
        ) {
            refresh()
        }
    }

    private suspend fun onStart(granted: Boolean, rationale: Boolean) {
        val access = state.value.access
        if (state.value.isRecording || access !is RecordingAccess.Allowed) return
        if (access.quotaFull) return reduce(RecordingsMutation.Message(RecordingsMessage.QUOTA_REACHED))
        when {
            granted -> start()
            rationale -> reduce(RecordingsMutation.Mic(MicPrompt.RATIONALE))
            else -> sendEffect(RecordingsEffect.RequestMicPermission)
        }
    }

    private suspend fun onPermission(granted: Boolean, rationale: Boolean) {
        if (granted) {
            reduce(RecordingsMutation.Mic(null))
            if (state.value.access is RecordingAccess.Allowed && !state.value.isRecording) start()
        } else {
            reduce(RecordingsMutation.Mic(if (rationale) MicPrompt.RATIONALE else MicPrompt.BLOCKED))
        }
    }

    private suspend fun start() {
        stopPlayback()
        val started = startRecording().getOrElse { return onStartFailure(it) }
        handle = started
        reduce(RecordingsMutation.RecordingStarted)
        session = viewModelScope.launch {
            val ticker = launch {
                var elapsed = 0L
                while (true) {
                    delay(TICK_MS)
                    elapsed += TICK_MS
                    reduce(RecordingsMutation.Tick(elapsed))
                }
            }
            // Parada automatica al limite o fallo del grabador: se resuelve como si el usuario pulsara "Parar".
            started.outcome.filterNotNull().first()
            ticker.cancel()
            onIntent(RecordingsIntent.Stop)
        }
    }

    private suspend fun onStartFailure(error: Throwable) {
        when (error) {
            RecordingFailure.PermissionDenied -> sendEffect(RecordingsEffect.RequestMicPermission)
            RecordingFailure.ConsentRequired, RecordingFailure.Disabled -> refresh()
            RecordingFailure.QuotaReached -> {
                reduce(RecordingsMutation.Message(RecordingsMessage.QUOTA_REACHED))
                refresh()
            }
            RecordingFailure.Busy, RecordingFailure.MicUnavailable ->
                reduce(RecordingsMutation.Message(RecordingsMessage.MIC_BUSY))
            else -> reduce(RecordingsMutation.Message(RecordingsMessage.RECORDING_FAILED))
        }
    }

    private suspend fun onStop() {
        val current = handle ?: return
        endSession()
        val take = current.stop().getOrElse { error ->
            val message = if (error == RecordingFailure.TooShort) {
                RecordingsMessage.TOO_SHORT
            } else {
                RecordingsMessage.RECORDING_FAILED
            }
            return reduce(RecordingsMutation.Message(message))
        }
        finishRecording(take, demo?.assignmentId, demo?.studentUid).fold(
            onSuccess = {
                reduce(RecordingsMutation.Message(RecordingsMessage.SAVED))
                refresh()
            },
            onFailure = { reduce(RecordingsMutation.Message(RecordingsMessage.SAVE_FAILED)) }
        )
    }

    private suspend fun onLeave() {
        stopPlayback()
        val current = handle ?: return
        endSession()
        current.discard()
    }

    private fun endSession() {
        handle = null
        session?.cancel()
        session = null
        reduce(RecordingsMutation.RecordingEnded)
    }

    private suspend fun onDelete() {
        val id = state.value.confirmDeleteId ?: return
        reduce(RecordingsMutation.DeletePrompt(null))
        val uid = state.value.uid ?: return
        deleteRecording(uid, id).fold(
            onSuccess = { refresh() },
            onFailure = { reduce(RecordingsMutation.Message(RecordingsMessage.DELETE_FAILED)) }
        )
    }

    private fun onPlay(id: String) {
        if (state.value.isRecording) return
        val recording = state.value.recordings.firstOrNull { it.id == id && it.ready } ?: return
        playJob?.cancel()
        playJob = viewModelScope.launch { playback.play(recording) }
    }

    private fun stopPlayback() {
        playJob?.cancel()
        playJob = null
        playback.stop()
    }

    private fun onToggleReviews(id: String) {
        reviewsJob?.cancel()
        if (state.value.reviewsFor == id) return reduce(RecordingsMutation.ReviewsOpened(null))
        if (state.value.recordings.none { it.id == id && it.ready }) return
        reduce(RecordingsMutation.ReviewsOpened(id))
        reviewsJob = viewModelScope.launch {
            observeReviews(id)
                .catch { reduce(RecordingsMutation.ReviewsFailed(id)) }
                .collect { reduce(RecordingsMutation.ReviewsLoaded(id, it)) }
        }
    }

    private fun RecordingsOverview.onlyDemo(): RecordingsOverview {
        val target = demo ?: return this
        return copy(
            pending = pending.filter { it.assignmentId == target.assignmentId && it.sharedWith == target.studentUid },
            recordings = recordings.filter {
                it.assignmentId == target.assignmentId && it.sharedWith == target.studentUid
            }
        )
    }

    private suspend fun withUid(action: suspend (String) -> Result<Unit>) {
        val uid = state.value.uid ?: return
        action(uid).onFailure { reduce(RecordingsMutation.Message(RecordingsMessage.ACTION_FAILED)) }
    }

    private suspend fun busy(block: suspend () -> Unit) {
        reduce(RecordingsMutation.Busy(true))
        try {
            block()
        } finally {
            reduce(RecordingsMutation.Busy(false))
        }
    }

    /** Si el ViewModel muere con una toma abierta (sin pasar por `Leave`), se descarta fuera de su ambito. */
    @OptIn(DelicateCoroutinesApi::class)
    override fun onCleared() {
        handle?.let { current -> GlobalScope.launch { current.discard() } }
        handle = null
        playback.stop()
        super.onCleared()
    }

    private fun reduce(mutation: RecordingsMutation) = setState { RecordingsReducer.reduce(this, mutation) }

    companion object {
        const val TICK_MS = 250L
        const val STUDENT_UID_KEY = "studentUid"
        const val ASSIGNMENT_ID_KEY = "assignmentId"
    }
}

/** Alumno y tarea de una demo; los nombres son los argumentos de `TeacherDemoDestination`. */
private data class DemoTarget(val studentUid: String, val assignmentId: String) {
    override fun toString(): String = "DemoTarget"

    companion object {
        fun from(handle: SavedStateHandle): DemoTarget? {
            val student = handle.get<String>(RecordingsViewModel.STUDENT_UID_KEY) ?: return null
            val assignment = handle.get<String>(RecordingsViewModel.ASSIGNMENT_ID_KEY) ?: return null
            return DemoTarget(student, assignment)
        }
    }
}
