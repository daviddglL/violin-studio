package com.violinstudio.ui.feature.recording.viewmodel

object AssignmentDemosReducer {
    fun reduce(state: AssignmentDemosState, mutation: AssignmentDemosMutation): AssignmentDemosState = when (mutation) {
        AssignmentDemosMutation.Loading -> state.copy(isLoading = true, loadFailed = false)
        is AssignmentDemosMutation.Loaded -> state.copy(
            isLoading = false,
            loadFailed = false,
            demos = mutation.demos
        )
        AssignmentDemosMutation.LoadFailed -> state.copy(isLoading = false, loadFailed = true, demos = emptyList())
        is AssignmentDemosMutation.Playback -> state.copy(playback = mutation.state)
    }
}
