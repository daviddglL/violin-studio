package com.violinstudio.ui.feature.consent.viewmodel

import com.violinstudio.domain.feature.auth.usecase.SignOutUseCase
import com.violinstudio.domain.feature.consent.failure.ConsentFailure
import com.violinstudio.domain.feature.consent.usecase.AcceptPolicyUseCase
import com.violinstudio.domain.feature.consent.usecase.GetIdentityConfigUseCase
import com.violinstudio.domain.feature.session.SessionRefreshTrigger
import com.violinstudio.ui.commons.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/**
 * Consentimiento de adulto. La versión que se envía es siempre la de la política que la pantalla muestra (la del
 * estado de sesión o la recargada tras `PolicyOutdated`), nunca una constante del cliente. No navega: tras aceptar
 * se refrescan los claims y la sesión sustituye la pantalla; si el refresco falla se ofrece reintentar.
 */
@HiltViewModel
class ConsentViewModel @Inject constructor(
    private val acceptPolicy: AcceptPolicyUseCase,
    private val getConfig: GetIdentityConfigUseCase,
    private val signOut: SignOutUseCase,
    private val refreshTrigger: SessionRefreshTrigger
) : MviViewModel<ConsentState, ConsentIntent, ConsentEffect>(ConsentState()) {
    private var acceptPending = false

    override fun onIntent(intent: ConsentIntent) {
        when (intent) {
            ConsentIntent.Accept -> {
                if (acceptPending) return
                acceptPending = true
            }
            else -> Unit
        }
        super.onIntent(intent)
    }

    override suspend fun handleIntent(intent: ConsentIntent) = when (intent) {
        is ConsentIntent.SessionUpdated -> reduce(ConsentMutation.SessionUpdated(intent.config, intent.reason))
        is ConsentIntent.CheckedChanged -> reduce(ConsentMutation.CheckedChanged(intent.checked))
        ConsentIntent.OpenPolicy -> onOpenPolicy()
        ConsentIntent.PolicyLinkFailed -> reduce(ConsentMutation.PolicyLinkFailed)
        ConsentIntent.Accept -> onAccept()
        ConsentIntent.SignOut -> onSignOut()
    }

    private fun onOpenPolicy() {
        val url = state.value.config?.policyUrl ?: return
        reduce(ConsentMutation.OpenPolicyRequested)
        if (isSafePolicyUrl(url)) {
            sendEffect(ConsentEffect.OpenPolicy(url.trim()))
        } else {
            reduce(ConsentMutation.PolicyLinkFailed)
        }
    }

    private suspend fun onAccept() {
        try {
            reduce(ConsentMutation.AcceptRequested)
            val current = state.value
            val version = current.config?.policyVersion
            if (!current.isLoading || version == null) return
            val result = runCatchingNonCancellation { acceptPolicy(version) }
            result.fold(
                onSuccess = {
                    reduce(ConsentMutation.Succeeded)
                    refreshTrigger.requestRefresh()
                },
                onFailure = { reduce(failureMutation(it)) }
            )
        } finally {
            acceptPending = false
        }
    }

    private suspend fun failureMutation(failure: Throwable): ConsentMutation = when (failure) {
        is ConsentFailure.PolicyOutdated -> reloadPolicy()
        ConsentFailure.AlreadyGranted -> {
            refreshTrigger.requestRefresh()
            ConsentMutation.Succeeded
        }
        ConsentFailure.Network ->
            ConsentMutation.Failed(ConsentError.NETWORK)
        ConsentFailure.GuardianRequired,
        ConsentFailure.UnderageNotAllowed ->
            ConsentMutation.Failed(ConsentError.GUARDIAN_REQUIRED)
        else -> ConsentMutation.Failed(ConsentError.UNKNOWN)
    }

    /** El servidor dice que la versión es antigua: se recarga la política vigente y se pide aceptarla de nuevo. */
    private suspend fun reloadPolicy(): ConsentMutation {
        val result = runCatchingNonCancellation { getConfig() }
        // La sesión sigue con la política cacheada: que vuelva a consultarla para resolver con la versión nueva.
        refreshTrigger.requestRefresh()
        return result.fold(
            onSuccess = { ConsentMutation.PolicyOutdated(it) },
            onFailure = { ConsentMutation.Failed(ConsentError.POLICY_UNAVAILABLE) }
        )
    }

    private suspend fun onSignOut() {
        // El estado de sesión sigue siendo la fuente de verdad; un fallo no debe matar el bucle de intents.
        try {
            signOut()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Sin registrar la causa.
        }
    }

    private fun reduce(mutation: ConsentMutation) = setState { ConsentReducer.reduce(this, mutation) }
}

/** Una `Result.failure` del caso de uso o una excepción inesperada acaban igual; la cancelación sí se propaga. */
private suspend fun <T> runCatchingNonCancellation(block: suspend () -> Result<T>): Result<T> = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
