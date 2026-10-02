package com.violinstudio.ui.feature.consent.viewmodel

object ConsentReducer {
    fun reduce(state: ConsentState, mutation: ConsentMutation): ConsentState = when (mutation) {
        is ConsentMutation.SessionUpdated -> sessionUpdated(state, mutation)
        is ConsentMutation.CheckedChanged -> when {
            // Tras aceptar (o mientras se envía) la casilla ya no cuenta: la sesión sustituirá la pantalla.
            state.isLoading || state.succeeded || state.isDeleting -> state
            else -> state.copy(
                checked = mutation.checked,
                error = state.error.takeIf { it == ConsentError.POLICY_CHANGED }
            )
        }
        ConsentMutation.OpenPolicyRequested -> state.copy(policyLinkFailed = false)
        ConsentMutation.PolicyLinkFailed -> state.copy(policyLinkFailed = true)
        ConsentMutation.AcceptRequested ->
            if (state.canAccept) state.copy(isLoading = true, error = null, deleteError = null) else state
        ConsentMutation.Succeeded -> state.copy(isLoading = false, succeeded = true)
        // La política recargada manda: hay que leerla y aceptarla de nuevo.
        is ConsentMutation.PolicyOutdated ->
            state.copy(
                config = mutation.config,
                checked = false,
                isLoading = false,
                error = ConsentError.POLICY_CHANGED
            )
        is ConsentMutation.Failed -> state.copy(isLoading = false, error = mutation.error)
        ConsentMutation.DeleteStarted -> state.copy(isDeleting = true, deleteError = null)
        ConsentMutation.DeleteSucceeded -> state.copy(isDeleting = false)
        is ConsentMutation.DeleteFailed -> state.copy(isDeleting = false, deleteError = mutation.error)
    }

    /**
     * La sesión manda salvo que ya se haya recargado una política más nueva (tras `PolicyOutdated`): una versión
     * mayor reinicia casilla y error, una menor o igual solo actualiza el motivo.
     */
    private fun sessionUpdated(state: ConsentState, mutation: ConsentMutation.SessionUpdated): ConsentState {
        val current = state.config
        return if (current == null || mutation.config.policyVersion > current.policyVersion) {
            state.copy(config = mutation.config, reason = mutation.reason, checked = false, error = null)
        } else {
            state.copy(reason = mutation.reason)
        }
    }
}
