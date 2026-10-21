package com.violinstudio.ui.feature.recording.viewmodel

import com.violinstudio.domain.feature.recording.model.PlaybackState
import com.violinstudio.domain.feature.recording.model.RecordingSummary
import com.violinstudio.ui.commons.mvi.UiEffect
import com.violinstudio.ui.commons.mvi.UiIntent
import com.violinstudio.ui.commons.mvi.UiState

/** Demostraciones que el profesor grabo para una tarea del alumno: solo escuchar. */
data class AssignmentDemosState(
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val demos: List<RecordingSummary> = emptyList(),
    val playback: PlaybackState = PlaybackState.Idle
) : UiState {
    override fun toString(): String =
        "AssignmentDemosState(isLoading=$isLoading, loadFailed=$loadFailed, demos=${demos.size})"
}

sealed interface AssignmentDemosIntent : UiIntent {
    data object Retry : AssignmentDemosIntent

    data class Play(val id: String) : AssignmentDemosIntent

    data object Stop : AssignmentDemosIntent

    /** Salir de la pantalla o pasar a segundo plano: deja de sonar. */
    data object Leave : AssignmentDemosIntent
}

sealed interface AssignmentDemosEffect : UiEffect

sealed interface AssignmentDemosMutation {
    data object Loading : AssignmentDemosMutation

    data class Loaded(val demos: List<RecordingSummary>) : AssignmentDemosMutation

    data object LoadFailed : AssignmentDemosMutation

    data class Playback(val state: PlaybackState) : AssignmentDemosMutation
}
