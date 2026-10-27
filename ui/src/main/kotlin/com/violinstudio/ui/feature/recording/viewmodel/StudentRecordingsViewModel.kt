package com.violinstudio.ui.feature.recording.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.recording.model.Verdict
import com.violinstudio.domain.feature.recording.usecase.ObserveMyReviewUseCase
import com.violinstudio.domain.feature.recording.usecase.ObserveStudentRecordingsUseCase
import com.violinstudio.domain.feature.recording.usecase.RecordingPlaybackUseCase
import com.violinstudio.domain.feature.recording.usecase.SaveReviewUseCase
import com.violinstudio.ui.commons.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Grabaciones de un alumno vinculado para su profesor (REQ-RVW-01): escuchar (en memoria) y dejar o cambiar su
 * valoracion. El `studentUid` llega por la ruta. Las reglas deciden que puede leer y escribir; si deniegan, la lista
 * muestra el fallo y se puede reintentar. Sin logs ni datos en errores (leccion 8).
 */
@HiltViewModel
class StudentRecordingsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val observeRecordings: ObserveStudentRecordingsUseCase,
    private val observeMyReview: ObserveMyReviewUseCase,
    private val saveReview: SaveReviewUseCase,
    private val playback: RecordingPlaybackUseCase
) : MviViewModel<StudentRecordingsState, StudentRecordingsIntent, StudentRecordingsEffect>(
    StudentRecordingsState()
) {
    private val studentUid: String? = savedStateHandle.get<String>(STUDENT_UID_KEY)
    private var listObserver: Job? = null
    private var playJob: Job? = null
    private var editorJob: Job? = null

    init {
        viewModelScope.launch { playback.state.collect { reduce(StudentRecordingsMutation.Playback(it)) } }
        start()
    }

    private fun start() {
        listObserver?.cancel()
        reduce(StudentRecordingsMutation.Loading)
        val uid = studentUid ?: return reduce(StudentRecordingsMutation.LoadFailed)
        listObserver = viewModelScope.launch {
            try {
                observeRecordings(uid).collect { reduce(StudentRecordingsMutation.Loaded(it)) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                reduce(StudentRecordingsMutation.LoadFailed)
            }
        }
    }

    override suspend fun handleIntent(intent: StudentRecordingsIntent) {
        when (intent) {
            StudentRecordingsIntent.Retry -> if (state.value.loadFailed) start()

            is StudentRecordingsIntent.Play -> onPlay(intent.id)

            StudentRecordingsIntent.Stop, StudentRecordingsIntent.Leave -> stopPlayback()

            is StudentRecordingsIntent.OpenReview -> onOpenReview(intent.id)

            is StudentRecordingsIntent.SetVerdict -> reduce(StudentRecordingsMutation.VerdictChosen(intent.verdict))

            is StudentRecordingsIntent.SetText -> reduce(StudentRecordingsMutation.Text(intent.text))

            StudentRecordingsIntent.SaveReview -> onSave()

            StudentRecordingsIntent.CloseReview -> {
                editorJob?.cancel()
                reduce(StudentRecordingsMutation.EditorClosed)
            }

            StudentRecordingsIntent.DismissMessage -> reduce(StudentRecordingsMutation.Message(null))
        }
    }

    private fun onPlay(id: String) {
        val recording = state.value.recordings.firstOrNull { it.id == id } ?: return
        playJob?.cancel()
        playJob = viewModelScope.launch { playback.play(recording) }
    }

    private fun stopPlayback() {
        playJob?.cancel()
        playJob = null
        playback.stop()
    }

    private fun onOpenReview(id: String) {
        val uid = studentUid ?: return
        if (state.value.recordings.none { it.id == id }) return
        editorJob?.cancel()
        reduce(StudentRecordingsMutation.EditorOpened(id))
        editorJob = viewModelScope.launch {
            try {
                reduce(StudentRecordingsMutation.EditorLoaded(id, observeMyReview(uid, id).first()))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                reduce(StudentRecordingsMutation.EditorClosed)
                reduce(StudentRecordingsMutation.Message(StudentRecordingsMessage.REVIEW_LOAD_FAILED))
            }
        }
    }

    /** Va en su propia coroutine: esperar al servidor no debe retener "Parar" ni salir de la pantalla. */
    private fun onSave() {
        val uid = studentUid ?: return
        val editor = state.value.editor?.takeIf { it.canSave } ?: return
        val verdict = editor.verdict ?: return
        reduce(StudentRecordingsMutation.Saving(true))
        viewModelScope.launch { save(uid, editor.recordingId, verdict, editor.text) }
    }

    private suspend fun save(uid: String, recordingId: String, verdict: Verdict, text: String) {
        saveReview(uid, recordingId, verdict, text).fold(
            onSuccess = {
                reduce(StudentRecordingsMutation.EditorClosed)
                reduce(StudentRecordingsMutation.Message(StudentRecordingsMessage.SAVED))
            },
            onFailure = {
                reduce(StudentRecordingsMutation.Saving(false))
                reduce(StudentRecordingsMutation.Message(StudentRecordingsMessage.SAVE_FAILED))
            }
        )
    }

    override fun onCleared() {
        playback.stop()
        super.onCleared()
    }

    private fun reduce(mutation: StudentRecordingsMutation) =
        setState { StudentRecordingsReducer.reduce(this, mutation) }

    companion object {
        /** Nombre del argumento de `StudentRecordingsDestination`. */
        const val STUDENT_UID_KEY = "studentUid"
    }
}
