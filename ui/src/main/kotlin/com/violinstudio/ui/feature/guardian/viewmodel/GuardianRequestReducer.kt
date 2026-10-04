package com.violinstudio.ui.feature.guardian.viewmodel

import com.violinstudio.domain.feature.auth.usecase.isPlausibleEmail

object GuardianRequestReducer {
    fun reduce(state: GuardianRequestState, mutation: GuardianRequestMutation): GuardianRequestState {
        return when (mutation) {
            is GuardianRequestMutation.SessionUpdated -> state.copy(reason = mutation.reason)
            is GuardianRequestMutation.EmailChanged ->
                if (state.isLoading || state.succeeded || state.isDeleting) {
                    state
                } else {
                    state.copy(email = mutation.email, emailError = null, error = null, retryAfterSeconds = null)
                }
            GuardianRequestMutation.SubmitRequested -> submit(state)
            GuardianRequestMutation.Succeeded -> state.copy(isLoading = false, succeeded = true)
            GuardianRequestMutation.EmailRejected ->
                state.copy(isLoading = false, emailError = GuardianEmailError.INVALID)
            is GuardianRequestMutation.Failed ->
                state.copy(isLoading = false, error = mutation.error, retryAfterSeconds = mutation.retryAfterSeconds)
            GuardianRequestMutation.DeleteStarted -> state.copy(isDeleting = true, deleteError = null)
            GuardianRequestMutation.DeleteSucceeded -> state.copy(isDeleting = false)
            is GuardianRequestMutation.DeleteFailed -> state.copy(isDeleting = false, deleteError = mutation.error)
        }
    }

    private fun submit(state: GuardianRequestState): GuardianRequestState = when {
        !state.canSubmit -> state
        !isPlausibleEmail(state.email.trim()) -> state.copy(emailError = GuardianEmailError.INVALID)
        else -> state.copy(isLoading = true, error = null, retryAfterSeconds = null, deleteError = null)
    }
}
