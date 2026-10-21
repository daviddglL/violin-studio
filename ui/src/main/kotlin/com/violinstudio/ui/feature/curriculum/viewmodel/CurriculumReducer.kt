package com.violinstudio.ui.feature.curriculum.viewmodel

object CurriculumReducer {
    fun reduce(state: CurriculumState, mutation: CurriculumMutation): CurriculumState = when (mutation) {
        is CurriculumMutation.Loaded -> state.copy(progress = mutation.progress)
        is CurriculumMutation.Toggled ->
            state.copy(expandedId = if (state.expandedId == mutation.id) null else mutation.id)
        CurriculumMutation.SaveFailed -> state.copy(message = CurriculumMessage.SAVE_FAILED)
        CurriculumMutation.MessageDismissed -> state.copy(message = null)
    }
}
