package com.violinstudio.ui.feature.consent.viewmodel

object ConsentReducer {
    fun reduce(state: ConsentState, mutation: ConsentMutation): ConsentState = when (mutation) {
        is ConsentMutation.SessionUpdated -> sessionUpdated(state, mutation)
        is ConsentMutation.CheckedChanged -> when {
            // Tras aceptar (o mientras se envía) la casilla ya no cuenta: la sesión sustituirá la pantalla.
            state.isLoading || state.succeeded -> state
            else -> state.copy(
                checked = mutation.checked,
                error = state.error.takeIf { it == ConsentError.POLICY_CHANGED }
            )
        }
        ConsentMutation.OpenPolicyRequested -> state.copy(policyLinkFailed = false)
        ConsentMutation.PolicyLinkFailed -> state.copy(policyLinkFailed = true)
        ConsentMutation.AcceptRequested ->
            if (state.canAccept) state.copy(isLoading = true, error = null) else state
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
    }

    /**
     * La sesión manda salvo que ya se haya recargado una política más nueva (tras `PolicyOutdated`): una versión
     * mayor reinicia casilla y error, una menor o igual solo actualiza el motivo. Una versión o un motivo distintos
     * levantan además el bloqueo de `succeeded`: la sesión no avanzó con lo aceptado y hay que poder aceptar de nuevo.
     */
    private fun sessionUpdated(state: ConsentState, mutation: ConsentMutation.SessionUpdated): ConsentState {
        val current = state.config
        val changed = current?.policyVersion != mutation.config.policyVersion || state.reason != mutation.reason
        val base = if (changed) state.copy(succeeded = false) else state
        return if (current == null || mutation.config.policyVersion > current.policyVersion) {
            base.copy(config = mutation.config, reason = mutation.reason, checked = false, error = null)
        } else {
            base.copy(reason = mutation.reason)
        }
    }
}
