package com.violinstudio.ui.feature.assignment.viewmodel

object AssignmentEditReducer {
    fun reduce(state: AssignmentEditState, mutation: AssignmentEditMutation): AssignmentEditState = when (mutation) {
        is AssignmentEditMutation.TitleEdited ->
            state.copy(title = mutation.title, titleTouched = true, error = null)
        is AssignmentEditMutation.DescriptionEdited -> state.copy(description = mutation.description, error = null)
        is AssignmentEditMutation.DueAtChanged -> state.copy(dueAt = mutation.dueAt, error = null)
        AssignmentEditMutation.SaveAttempted -> state.copy(saveAttempted = true)
        is AssignmentEditMutation.Loaded -> state.copy(
            title = mutation.title,
            description = mutation.description.orEmpty(),
            dueAt = mutation.dueAt,
            isEditing = true
        )
        is AssignmentEditMutation.Busy -> state.copy(busy = mutation.busy)
        is AssignmentEditMutation.Failed -> state.copy(busy = false, error = mutation.error)
        AssignmentEditMutation.ErrorDismissed -> state.copy(error = null)
    }
}
