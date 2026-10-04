package com.violinstudio.ui.feature.guardian.viewmodel

import com.violinstudio.domain.feature.account.failure.AccountFailure
import com.violinstudio.domain.feature.account.usecase.DeleteAccountUseCase
import com.violinstudio.domain.feature.auth.usecase.SignOutUseCase
import com.violinstudio.domain.feature.consent.failure.ConsentFailure
import com.violinstudio.domain.feature.consent.usecase.RequestGuardianConsentUseCase
import com.violinstudio.domain.feature.session.SessionRefreshTrigger
import com.violinstudio.ui.commons.mvi.MviViewModel
import com.violinstudio.ui.commons.mvi.UiEffect
import com.violinstudio.ui.feature.consent.viewmodel.ConsentDeleteError
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/**
 * Consentimiento de un menor: pide al servidor que envíe el enlace al tutor. No navega: el servidor deja la cuenta en
 * `parental_pending` y la sesión sustituye la pantalla. La respuesta es genérica (no se muestra nada del tutor).
 */
@HiltViewModel
class GuardianRequestViewModel @Inject constructor(
    private val requestConsent: RequestGuardianConsentUseCase,
    private val deleteAccount: DeleteAccountUseCase,
    private val signOut: SignOutUseCase,
    private val refreshTrigger: SessionRefreshTrigger
) : MviViewModel<GuardianRequestState, GuardianRequestIntent, UiEffect>(GuardianRequestState()) {
    private var submitPending = false
    private var deletePending = false

    override fun onIntent(intent: GuardianRequestIntent) {
        when (intent) {
            GuardianRequestIntent.SubmitGuardianEmail -> {
                if (submitPending || deletePending) return
                submitPending = true
            }
            GuardianRequestIntent.DeleteAccount -> {
                if (deletePending || submitPending) return
                deletePending = true
            }
            else -> Unit
        }
        super.onIntent(intent)
    }

    override suspend fun handleIntent(intent: GuardianRequestIntent) = when (intent) {
        is GuardianRequestIntent.SessionUpdated -> reduce(GuardianRequestMutation.SessionUpdated(intent.reason))
        is GuardianRequestIntent.EmailChanged -> reduce(GuardianRequestMutation.EmailChanged(intent.email))
        GuardianRequestIntent.SubmitGuardianEmail -> onSubmit()
        GuardianRequestIntent.DeleteAccount -> onDelete()
        GuardianRequestIntent.SignOut -> onSignOut()
    }

    private suspend fun onSubmit() {
        try {
            reduce(GuardianRequestMutation.SubmitRequested)
            val current = state.value
            // Un formato inválido no pasa de aquí: ni siquiera sale del dispositivo.
            if (!current.isLoading) return
            runCatchingNonCancellation { requestConsent(current.email) }.fold(
                onSuccess = { reduce(GuardianRequestMutation.Succeeded) },
                onFailure = { reduce(failureMutation(it)) }
            )
        } finally {
            submitPending = false
        }
    }

    private fun failureMutation(failure: Throwable): GuardianRequestMutation = when (failure) {
        ConsentFailure.GuardianEmailInvalid -> GuardianRequestMutation.EmailRejected
        ConsentFailure.AlreadyGranted -> {
            // La sesión puede no haberlo visto aún: que se resuelva de nuevo.
            refreshTrigger.requestRefresh()
            GuardianRequestMutation.Succeeded
        }
        is ConsentFailure.RateLimited ->
            GuardianRequestMutation.Failed(GuardianRequestError.RATE_LIMITED, failure.retryAfterSeconds)
        ConsentFailure.NotMinor -> GuardianRequestMutation.Failed(GuardianRequestError.NOT_MINOR)
        ConsentFailure.Network -> GuardianRequestMutation.Failed(GuardianRequestError.NETWORK)
        else -> GuardianRequestMutation.Failed(GuardianRequestError.UNKNOWN)
    }

    private suspend fun onDelete() {
        try {
            reduce(GuardianRequestMutation.DeleteStarted)
            // Éxito: el repositorio cierra la sesión y el host sustituye la pantalla; no se afirma nada más.
            runCatchingNonCancellation { deleteAccount() }.fold(
                onSuccess = { reduce(GuardianRequestMutation.DeleteSucceeded) },
                onFailure = { reduce(GuardianRequestMutation.DeleteFailed(it.toDeleteError())) }
            )
        } finally {
            deletePending = false
        }
    }

    private suspend fun onSignOut() {
        try {
            signOut()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Sin registrar la causa; la sesión sigue siendo la fuente de verdad.
        }
    }

    private fun Throwable.toDeleteError() = when (this) {
        AccountFailure.RequiresRecentLogin -> ConsentDeleteError.REAUTH_REQUIRED
        AccountFailure.Network -> ConsentDeleteError.NETWORK
        else -> ConsentDeleteError.FAILED
    }

    private fun reduce(mutation: GuardianRequestMutation) =
        setState { GuardianRequestReducer.reduce(this, mutation) }
}

private suspend fun <T> runCatchingNonCancellation(block: suspend () -> Result<T>): Result<T> = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
