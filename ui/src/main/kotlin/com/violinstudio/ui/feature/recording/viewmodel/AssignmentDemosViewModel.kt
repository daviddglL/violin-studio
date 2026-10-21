package com.violinstudio.ui.feature.recording.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.recording.usecase.ObserveAssignmentDemosUseCase
import com.violinstudio.domain.feature.recording.usecase.RecordingPlaybackUseCase
import com.violinstudio.ui.commons.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Demostraciones del profesor para una tarea, vistas por su alumno. `teacherUid` y `assignmentId` llegan por la ruta.
 * Las reglas exigen el vinculo y el consentimiento; si deniegan, la pantalla muestra el fallo y se puede reintentar.
 */
@HiltViewModel
class AssignmentDemosViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val observeDemos: ObserveAssignmentDemosUseCase,
    private val playback: RecordingPlaybackUseCase
) : MviViewModel<AssignmentDemosState, AssignmentDemosIntent, AssignmentDemosEffect>(AssignmentDemosState()) {
    private val teacherUid: String? = savedStateHandle.get<String>(TEACHER_UID_KEY)
    private val assignmentId: String? = savedStateHandle.get<String>(ASSIGNMENT_ID_KEY)
    private var listObserver: Job? = null
    private var playJob: Job? = null

    init {
        viewModelScope.launch { playback.state.collect { reduce(AssignmentDemosMutation.Playback(it)) } }
        start()
    }

    private fun start() {
        listObserver?.cancel()
        reduce(AssignmentDemosMutation.Loading)
        val teacher = teacherUid ?: return reduce(AssignmentDemosMutation.LoadFailed)
        val assignment = assignmentId ?: return reduce(AssignmentDemosMutation.LoadFailed)
        listObserver = viewModelScope.launch {
            try {
                observeDemos(teacher, assignment).collect { reduce(AssignmentDemosMutation.Loaded(it)) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                reduce(AssignmentDemosMutation.LoadFailed)
            }
        }
    }

    override suspend fun handleIntent(intent: AssignmentDemosIntent) {
        when (intent) {
            AssignmentDemosIntent.Retry -> if (state.value.loadFailed) start()
            is AssignmentDemosIntent.Play -> onPlay(intent.id)
            AssignmentDemosIntent.Stop, AssignmentDemosIntent.Leave -> stopPlayback()
        }
    }

    private fun onPlay(id: String) {
        val demo = state.value.demos.firstOrNull { it.id == id } ?: return
        playJob?.cancel()
        playJob = viewModelScope.launch { playback.play(demo) }
    }

    private fun stopPlayback() {
        playJob?.cancel()
        playJob = null
        playback.stop()
    }

    override fun onCleared() {
        playback.stop()
        super.onCleared()
    }

    private fun reduce(mutation: AssignmentDemosMutation) = setState { AssignmentDemosReducer.reduce(this, mutation) }

    companion object {
        /** Nombres de los argumentos de `AssignmentDemosDestination`. */
        const val TEACHER_UID_KEY = "teacherUid"
        const val ASSIGNMENT_ID_KEY = "assignmentId"
    }
}
